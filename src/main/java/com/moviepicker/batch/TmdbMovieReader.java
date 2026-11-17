package com.moviepicker.batch;

import java.util.List;

import com.moviepicker.tmdb.TmdbClient;
import com.moviepicker.tmdb.TmdbList;
import com.moviepicker.tmdb.dto.TmdbMoviePage;
import com.moviepicker.tmdb.dto.TmdbMovieSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

/**
 * Walks  list x page  against TMDB and returns ONE movie per read() call; returns null when everything is read
 * (null is the framework's "end of input" signal).
 *
 * WHY a custom reader? Spring Batch ships readers for files, JDBC, JPA, ... but none for REST APIs.
 * WHY not AbstractPaginatedDataItemReader? It is built for ONE paged source with a FIXED page size: its restart logic
 * does "jump to item N" = page N / pageSize. We walk SEVERAL lists (different lengths, last page shorter), so a single
 * item counter cannot be mapped back to a (list, page). We store the position ourselves instead.
 *
 * ITEMSTREAM LIFECYCLE (this class is an ItemStream, and the step registers it automatically):
 *   open(ctx)   - called ONCE before the first read. Fresh run: ctx is empty -> start at the beginning.
 *                 Restarted run: ctx holds what update() saved in the last COMMITTED chunk -> resume there.
 *   update(ctx) - called by the framework at the end of EVERY chunk, just before the chunk's transaction commits.
 *                 What we put here is saved to BATCH_STEP_EXECUTION_CONTEXT in the same commit. That is why a crash can
 *                 never leave "rows written but position not saved" (or the reverse) - both commit, or neither does.
 *   close()     - called once when the step ends (success or failure): release resources.
 *
 * Restart position = (listIndex, page, offset-within-page). On resume we RE-FETCH that page and skip `offset` items.
 * (With the real API the page content can shift between runs; that is an accepted limitation, and the upsert writer
 * makes any re-read item harmless anyway.)
 *
 * NOT thread-safe, by design. It is a @StepScope bean (one new instance per step execution), and a multi-threaded
 * step would need locking here; Phase 8 scales out with partitioning instead, giving each thread its own reader.
 */
public class TmdbMovieReader implements ItemStreamReader<SourcedMovie> {

	private static final Logger log = LoggerFactory.getLogger(TmdbMovieReader.class);

	static final String KEY_LIST_INDEX = "tmdb.reader.listIndex";
	static final String KEY_PAGE = "tmdb.reader.page";
	static final String KEY_OFFSET = "tmdb.reader.offset";
	/** Human-readable only: so you can SEE the position when you query BATCH_STEP_EXECUTION_CONTEXT. */
	static final String KEY_LIST_NAME = "tmdb.reader.list";

	private static final int TMDB_PAGE_SIZE = 20;

	private final TmdbClient client;
	private final List<TmdbList> lists;
	private final int pagesPerList;

	// ---- state that is persisted (see update/open)
	private int listIndex = 0;
	private int page = 1;
	private int offset = 0;

	// ---- state that is NOT persisted (re-derived by re-fetching)
	private List<TmdbMovieSummary> buffer;   // results of the current page, null = not fetched yet

	public TmdbMovieReader(TmdbClient client, List<TmdbList> lists, int pagesPerList) {
		this.client = client;
		this.lists = List.copyOf(lists);
		this.pagesPerList = pagesPerList;
	}

	@Override
	public void open(ExecutionContext ctx) {
		if (ctx.containsKey(KEY_LIST_INDEX)) {
			listIndex = ctx.getInt(KEY_LIST_INDEX);
			page = ctx.getInt(KEY_PAGE);
			offset = ctx.getInt(KEY_OFFSET);
			log.info("Reader RESUMING from saved state: list={} page={} offset={}", currentListName(), page, offset);
		}
		else {
			listIndex = 0;
			page = 1;
			offset = 0;
			log.info("Reader starting fresh: {} list(s) x up to {} page(s)", lists.size(), pagesPerList);
		}
		buffer = null;
	}

	@Override
	public void update(ExecutionContext ctx) {
		ctx.putInt(KEY_LIST_INDEX, listIndex);
		ctx.putInt(KEY_PAGE, page);
		ctx.putInt(KEY_OFFSET, offset);
		ctx.putString(KEY_LIST_NAME, currentListName());
	}

	@Override
	public void close() {
		buffer = null;
	}

	@Override
	public SourcedMovie read() {
		while (listIndex < lists.size()) {
			if (page > pagesPerList) {
				nextList();
				continue;
			}
			if (buffer == null) {
				TmdbList list = lists.get(listIndex);
				TmdbMoviePage fetched = client.fetchPage(list, page);
				log.debug("Fetched {} page {}/{} ({} results)", list.listName(), page, fetched.totalPages(),
						fetched.results().size());
				if (fetched.results().isEmpty() || page > fetched.totalPages()) {
					nextList();                       // list exhausted before pagesPerList was reached
					continue;
				}
				buffer = fetched.results();
			}
			if (offset >= buffer.size()) {
				page++;
				offset = 0;
				buffer = null;
				continue;
			}
			int index = offset++;
			return new SourcedMovie(lists.get(listIndex), page, (page - 1) * TMDB_PAGE_SIZE + index + 1,
					buffer.get(index));
		}
		return null;                                  // end of input
	}

	private void nextList() {
		listIndex++;
		page = 1;
		offset = 0;
		buffer = null;
	}

	private String currentListName() {
		return listIndex < lists.size() ? lists.get(listIndex).listName() : "(done)";
	}
}
