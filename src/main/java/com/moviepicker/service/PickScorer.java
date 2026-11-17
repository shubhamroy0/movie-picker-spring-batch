package com.moviepicker.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.moviepicker.domain.Movie;

/**
 * The "picks" ranking:   score = WR * ln(1 + votes) + recencyBonus
 *
 *  WR (weighted rating) = v/(v+m) * R  +  m/(v+m) * C        (the Bayesian average IMDb popularised)
 *      R = the movie's average rating, v = its vote count, C = mean rating of all candidates, m = 1000 votes
 *      Why: a 9.8 from 3 votes must not beat an 8.5 from 30,000. With few votes WR is pulled toward the global mean C;
 *      with many votes it converges to R.
 *  ln(1 + votes)  = a CONFIDENCE/popularity multiplier with diminishing returns: 100 -> 4.6, 10,000 -> 9.2, 1,000,000 -> 13.8.
 *      A log keeps blockbusters ahead without letting raw vote counts swamp quality.
 *  recencyBonus = 10 * e^(-ageInYears / 3)   -> +10 for a brand-new release, ~+3.7 at 3 years, ~+0.9 at 7, ~0 for classics.
 *      Gives new films a nudge (they have fewer votes by nature) without burying old masterpieces.
 * Typical scores: a classic 8.7 with 28,000 votes ~ 90; a 2024 film 7.6 with 5,500 votes ~ 76. Tune m, the log and the
 * bonus to taste: it is a heuristic, not a law.
 */
public final class PickScorer {

	public static final double MIN_VOTES_FOR_FULL_WEIGHT = 1000;
	private static final double RECENCY_MAX_BONUS = 10;
	private static final double RECENCY_DECAY_YEARS = 3;

	private PickScorer() {
	}

	public static double weightedRating(double rating, int votes, double meanRating) {
		double v = votes;
		double m = MIN_VOTES_FOR_FULL_WEIGHT;
		return (v / (v + m)) * rating + (m / (v + m)) * meanRating;
	}

	public static double recencyBonus(LocalDate releaseDate, LocalDate today) {
		if (releaseDate == null) {
			return 0;
		}
		double ageYears = Math.max(0, ChronoUnit.DAYS.between(releaseDate, today) / 365.25);
		return RECENCY_MAX_BONUS * Math.exp(-ageYears / RECENCY_DECAY_YEARS);
	}

	public static double score(Movie m, double meanRating, LocalDate today) {
		return weightedRating(m.getVoteAverage(), m.getVoteCount(), meanRating) * Math.log1p(m.getVoteCount())
				+ recencyBonus(m.getReleaseDate(), today);
	}
}
