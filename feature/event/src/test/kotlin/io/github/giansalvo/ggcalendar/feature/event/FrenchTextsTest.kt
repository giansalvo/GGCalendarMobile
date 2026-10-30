package io.github.giansalvo.ggcalendar.feature.event

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.giansalvo.ggcalendar.core.data.DateFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * The French of the app made by the real code, with the resources as Android compiles them: the sentences of the recurrences (made of
 * pieces: "Toutes les 2 semaines le lundi, jeudi ; 5 fois"), the plurals ("0 invité", "2 invités"), the reminders, the dates, and the
 * no-break spaces that the compilation of the resources must keep. The files are checked as text by `StringResourcesTest` and
 * `FrenchStyleTest` in the app module; here it is what the person reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr-rFR") // with the region, as a phone has it: "fr" alone makes the JVM start the week on Sunday
class FrenchTextsTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val nb = " "

    private fun sentence(rrule: String) = describe(rrule, res, Locale.FRENCH)

    @Test
    fun everyDayWeekMonthYear() {
        assertEquals("Tous les jours", sentence("FREQ=DAILY"))
        assertEquals("Tous les 3 jours", sentence("FREQ=DAILY;INTERVAL=3"))
        assertEquals("Toutes les semaines", sentence("FREQ=WEEKLY"))
        assertEquals("Toutes les 2 semaines", sentence("FREQ=WEEKLY;INTERVAL=2"))
        assertEquals("Tous les mois", sentence("FREQ=MONTHLY"))
        assertEquals("Tous les 6 mois", sentence("FREQ=MONTHLY;INTERVAL=6"))
        assertEquals("Tous les ans", sentence("FREQ=YEARLY"))
        assertEquals("Tous les 2 ans", sentence("FREQ=YEARLY;INTERVAL=2"))
    }

    @Test
    fun weekdaysAndMonthDays() {
        assertEquals("Toutes les 2 semaines le lundi, jeudi", sentence("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH"))
        assertEquals("Tous les mois le 15", sentence("FREQ=MONTHLY;BYMONTHDAY=15"))
    }

    @Test
    fun theOrdinalAndTheWeekdayAgree() {
        // Every weekday name is masculine in French: "le premier lundi", "le dernier vendredi".
        assertEquals("Tous les mois le premier lundi", sentence("FREQ=MONTHLY;BYDAY=1MO"))
        assertEquals("Tous les mois le deuxième mardi", sentence("FREQ=MONTHLY;BYDAY=2TU"))
        assertEquals("Tous les mois le troisième mercredi", sentence("FREQ=MONTHLY;BYDAY=3WE"))
        assertEquals("Tous les mois le quatrième jeudi", sentence("FREQ=MONTHLY;BYDAY=4TH"))
        assertEquals("Tous les mois le dernier vendredi", sentence("FREQ=MONTHLY;BYDAY=-1FR"))
        assertEquals(listOf("dernier", "premier", "deuxième", "troisième", "quatrième", "cinquième"), res.getStringArray(R.array.ordinals).toList())
    }

    @Test
    fun theCountAndTheEndKeepTheNoBreakSpaceBeforeTheSemicolon() {
        assertEquals("Tous les jours$nb; 5 fois", sentence("FREQ=DAILY;COUNT=5"))
        assertEquals("Tous les jours$nb; une fois", sentence("FREQ=DAILY;COUNT=1"))
        assertEquals("Toutes les semaines le lundi$nb; 10 fois", sentence("FREQ=WEEKLY;BYDAY=MO;COUNT=10"))
        val until = sentence("FREQ=DAILY;UNTIL=20261231")!!
        assertTrue(until, until.startsWith("Tous les jours$nb; jusqu'au "))
        assertTrue(until, "2026" in until)
    }

    @Test
    fun pluralsOfFrenchCountZeroAsOne() {
        assertEquals("0 invité", res.getQuantityString(R.plurals.guests, 0, 0))
        assertEquals("1 invité", res.getQuantityString(R.plurals.guests, 1, 1))
        assertEquals("2 invités", res.getQuantityString(R.plurals.guests, 2, 2))
        assertTrue(res.getQuantityString(R.plurals.guests, 1_000_000, 1_000_000).endsWith("invités")) // "many" falls back to "other"
        assertEquals("1 événement exporté", res.getQuantityString(R.plurals.export_done, 1, 1))
        assertEquals("3 événements exportés", res.getQuantityString(R.plurals.export_done, 3, 3))
        assertEquals("1 occurrence exportée", res.getQuantityString(R.plurals.export_rows_done, 1, 1))
        assertEquals("4 occurrences exportées", res.getQuantityString(R.plurals.export_rows_done, 4, 4))
        assertEquals("1 événement non exporté$nb: il est privé.", res.getQuantityString(R.plurals.export_skipped_private, 1, 1))
        assertEquals("2 événements non exportés$nb: ils sont privés.", res.getQuantityString(R.plurals.export_skipped_private, 2, 2))
    }

    @Test
    fun reminders() {
        assertEquals("Au début", beforeText(0, res))
        assertEquals("1 minute avant", beforeText(1, res))
        assertEquals("10 minutes avant", beforeText(10, res))
        assertEquals("1 heure avant", beforeText(60, res))
        assertEquals("2 heures avant", beforeText(120, res))
        assertEquals("1 jour avant", beforeText(1440, res))
        assertEquals("2 jours avant", beforeText(2880, res))
        assertEquals("1 semaine avant", beforeText(10080, res))
    }

    @Test
    fun theNoBreakSpacesSurviveTheCompilationOfTheResources() {
        assertEquals("Fuseau horaire$nb: Europe/Rome", res.getString(R.string.time_zone_of, "Europe/Rome"))
        assertEquals("Supprimer cet événement$nb?", res.getString(R.string.delete_event_question))
        assertEquals("Prévenir les invités$nb?", res.getString(R.string.notify_guests_title))
        assertEquals("«${nb}x@y.fr$nb» n'est pas une adresse e-mail valide", res.getString(R.string.invalid_email_named, "x@y.fr"))
    }

    @Test
    fun theApostrophesAreStraightAndTheTextsAreNotCut() {
        assertEquals("Plus d'options", res.getString(R.string.more_options))
        assertEquals("Rappels par défaut de l'agenda", res.getString(R.string.calendar_defaults))
        assertEquals("Depuis Perso vers Travail.", res.getString(R.string.move_calendar_question, "Perso", "Travail"))
    }

    @Test
    fun theFrenchDatesAndTheFirstDayOfTheWeek() {
        val day = LocalDate.of(2026, 10, 8) // a Thursday
        val text = whenText(true, java.time.Instant.EPOCH, java.time.Instant.EPOCH, day, day.plusDays(1))
        assertTrue(text, text.startsWith("Jeudi 8 oct"))
        assertEquals("jeu. 8 oct.", day.format(DateFormats.of("EEEdMMM", Locale.FRANCE))) // the short form, as in the Schedule view
        assertEquals(DayOfWeek.MONDAY, WeekSettings.localeDefault())
    }
}
