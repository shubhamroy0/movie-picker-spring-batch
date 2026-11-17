package com.moviepicker.service;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The scoring formula is pure maths, so it is tested without Spring. */
class PickScorerTest {

	@Test
	void fewVotesArePulledTowardTheMeanManyVotesKeepTheirRating() {
		double mean = 6.5;
		double fewVotes = PickScorer.weightedRating(9.8, 3, mean);
		double manyVotes = PickScorer.weightedRating(8.5, 30_000, mean);

		assertThat(fewVotes).isBetween(6.5, 6.6);              // a 9.8 from 3 votes is basically "average"
		assertThat(manyVotes).isBetween(8.4, 8.5);            // 30k votes: the rating is trusted
		assertThat(manyVotes).isGreaterThan(fewVotes);
	}

	@Test
	void recencyBonusDecaysAndIsNeverNegative() {
		LocalDate today = LocalDate.of(2026, 11, 17);

		assertThat(PickScorer.recencyBonus(today, today)).isEqualTo(10.0);
		assertThat(PickScorer.recencyBonus(today.minusYears(3), today)).isBetween(3.5, 3.9);
		assertThat(PickScorer.recencyBonus(today.minusYears(40), today)).isLessThan(0.01);
		assertThat(PickScorer.recencyBonus(today.plusYears(1), today)).isEqualTo(10.0);   // future date: no bonus > max
		assertThat(PickScorer.recencyBonus(null, today)).isZero();
	}
}
