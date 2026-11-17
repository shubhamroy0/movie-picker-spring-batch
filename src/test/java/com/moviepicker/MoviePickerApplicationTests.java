package com.moviepicker;

import com.moviepicker.service.GenreCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Smoke test: the whole context (JPA, Batch metadata, properties binding, mock client) starts without a token. */
@SpringBootTest
@ActiveProfiles({"mock", "test"})
class MoviePickerApplicationTests {

	@Autowired
	GenreCatalog genreCatalog;

	@Test
	void contextLoadsAndGenresAreCachedAtStartup() {
		assertThat(genreCatalog.size()).isEqualTo(19);
		assertThat(genreCatalog.find(28)).get().extracting("name").isEqualTo("Action");
	}
}
