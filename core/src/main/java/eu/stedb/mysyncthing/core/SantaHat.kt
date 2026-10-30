package eu.stedb.mysyncthing.core

import java.time.LocalDate

/** A small easter egg: the app mark (widget, tabs) wears a Santa hat from 22 December to 6 January, by the phone's date. */
fun santaHat(day: LocalDate): Boolean =
    day.monthValue == 12 && day.dayOfMonth >= 22 || day.monthValue == 1 && day.dayOfMonth <= 6

/** The next day after [day] on which [santaHat] changes: 22 December or 7 January. */
fun santaHatChange(day: LocalDate): LocalDate =
    listOf(LocalDate.of(day.year, 1, 7), LocalDate.of(day.year, 12, 22), LocalDate.of(day.year + 1, 1, 7)).first { it > day }
