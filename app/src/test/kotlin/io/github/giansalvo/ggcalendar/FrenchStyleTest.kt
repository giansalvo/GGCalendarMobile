package io.github.giansalvo.ggcalendar

import io.github.giansalvo.ggcalendar.StringXml.Kind
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The way French is written in the app, the same as in the desktop app (`Strings.fr.json`) with one difference chosen on purpose:
 * a no-break space before `? : ; !` and inside « ». Terms: agenda (never "calendrier"), alarme (never "réveil"), rappel, invité,
 * événement, e-mail; "vous" and never "tu"; the infinitive on the buttons; a straight apostrophe and "…".
 */
class FrenchStyleTest {
    private class Text(val id: String, val base: String, val text: String)

    /** Every text of the app in French with the one it translates, plural forms and array items included. */
    private val texts: List<Text> by lazy {
        StringXml.modules.flatMap { module ->
            val base = StringXml.load(module, "values")!!
            val french = StringXml.load(module, "values-fr") ?: return@flatMap emptyList()
            base.entries.flatMap { (key, entry) ->
                val other = french.entries[key] ?: return@flatMap emptyList()
                entry.items.mapIndexedNotNull { n, (_, text) ->
                    val translated = other.items.getOrNull(n)?.second ?: return@mapIndexedNotNull null
                    Text("$module:$key" + if (entry.kind == Kind.STRING) "" else "[$n]", text, translated)
                }
            }
        }
    }

    private fun report(title: String, problems: List<String>) =
        assertTrue("$title (${problems.size}):\n" + problems.joinToString("\n"), problems.isEmpty())

    @Test
    fun theFrenchTextsExist() {
        assertTrue("no French text found: is values-fr there?", texts.size > 400)
    }

    @Test
    fun forbiddenWords() {
        val forbidden = mapOf(
            // (?U): \b and \w know the accented letters, or "complète" would contain the word "te".
            Regex("(?iU)(?<!mini-)\\bcalendriers?\\b") to "calendrier: say agenda",
            Regex("(?iU)\\br[ée]veils?\\b") to "réveil: say alarme",
            Regex("(?iU)\\b(tu|toi|ton|ta|tes|te)\\b") to "tutoiement: say vous",
            Regex("(?iU)\\bcourriels?\\b") to "courriel: say e-mail",
            Regex("’") to "typographic apostrophe: use the straight one",
        )
        report("forbidden words", texts.flatMap { t -> forbidden.filter { (re, _) -> re.containsMatchIn(t.text) }.map { "${t.id}: ${it.value}: ${t.text}" } })
    }

    @Test
    fun theTermsOfTheGlossaryAreUsed() {
        val required = listOf(
            Triple(Regex("\\bcalendars?\\b", RegexOption.IGNORE_CASE), Regex("agenda", RegexOption.IGNORE_CASE), "calendar -> agenda"),
            Triple(Regex("\\balarms?\\b", RegexOption.IGNORE_CASE), Regex("alarme", RegexOption.IGNORE_CASE), "alarm -> alarme"),
            Triple(Regex("\\breminders?\\b", RegexOption.IGNORE_CASE), Regex("rappel", RegexOption.IGNORE_CASE), "reminder -> rappel"),
            Triple(Regex("\\bguests?\\b", RegexOption.IGNORE_CASE), Regex("invit", RegexOption.IGNORE_CASE), "guest -> invité"),
            Triple(Regex("\\bevents?\\b", RegexOption.IGNORE_CASE), Regex("événement", RegexOption.IGNORE_CASE), "event -> événement"),
            Triple(Regex("\\be-?mail\\b", RegexOption.IGNORE_CASE), Regex("(?i)e-mail"), "email -> e-mail"),
        )
        val problems = texts.flatMap { t ->
            required.filter { (english, french, _) ->
                // "mini calendar" is a date picker, not an agenda: "mini-calendrier".
                english.containsMatchIn(t.base.replace(Regex("(?i)mini calendar"), "")) && !french.containsMatchIn(t.text)
            }.map { "${t.id}: ${it.third}: ${t.text}" }
        }
        report("glossary", problems)
    }

    @Test
    fun noBreakSpaceBeforeTallPunctuationAndInsideGuillemets() {
        val problems = mutableListOf<String>()
        for (t in texts) {
            val s = t.text
            if (Regex(" [?:;!]").containsMatchIn(s)) problems += "${t.id}: an ordinary space before ? : ; ! (use &#160;): $s"
            if (Regex("\\p{L}[?!;]").containsMatchIn(s)) problems += "${t.id}: no space before ? ! ; : $s"
            if (Regex("[\\p{L}\\p{N}%)]:(?!//|\\d)").containsMatchIn(s)) problems += "${t.id}: no space before a colon: $s"
            if (Regex("«(?!\\u00a0)").containsMatchIn(s) || Regex("(?<!\\u00a0)»").containsMatchIn(s)) problems += "${t.id}: « » without no-break spaces inside: $s"
        }
        report("no-break spaces", problems)
    }

    @Test
    fun ellipsisAndSpaces() {
        val problems = mutableListOf<String>()
        for (t in texts) {
            if ("..." in t.text) problems += "${t.id}: three dots, use …: ${t.text}"
            if ("  " in t.text) problems += "${t.id}: two spaces in a row: ${t.text}"
            if (t.text.startsWith(" ") != t.base.startsWith(" ") || t.text.endsWith(" ") != t.base.endsWith(" ")) problems += "${t.id}: spaces at the ends are not those of the default text"
        }
        report("ellipsis and spaces", problems)
    }

    @Test
    fun buttonsAreInTheInfinitive() {
        // "module:key" of the texts that are a command on a button: Enregistrer, Annuler, Supprimer…
        val buttons = listOf(
            "feature/event:string:save", "feature/event:string:cancel", "feature/event:string:delete", "feature/event:string:duplicate",
            "feature/event:string:close", "feature/event:string:edit", "feature/event:string:share", "feature/event:string:remove",
            "feature/event:string:discard", "feature/event:string:export_action", "feature/event:string:notify_guests_send",
            "feature/event:string:move_calendar_confirm", "feature/settings:string:settings_save", "feature/settings:string:settings_discard",
            "core/notifications:string:dismiss", "core/notifications:string:snooze", "core/notifications:string:intro_allow",
            "core/notifications:string:fix", "core/notifications:string:keep_alarm", "core/notifications:string:perm_change",
            "feature/calendar:string:authorize", "feature/calendar:string:retry", "feature/calendar:string:subscription_add_button",
            "feature/calendar:string:import_do", "feature/calendar:string:import_read", "feature/calendar:string:import_stop",
            "feature/calendar:string:import_continue", "feature/calendar:string:subscription_check", "feature/calendar:string:subscription_paste",
            "feature/calendar:string:local_calendar_delete", "feature/calendar:string:remove_account_confirm", "feature/calendar:string:account_add_action",
            "feature/calendar:string:log_save_file", "feature/calendar:string:subscription_refresh_now", "feature/calendar:string:clear",
        )
        val infinitive = Regex("^\\p{L}+(er|ir|re)(\\s.*)?$") // the first word: "Enregistrer le fichier", "Actualiser maintenant"
        val byId = texts.associateBy { it.id }
        report("infinitive", buttons.mapNotNull { id ->
            val t = byId[id]
            when {
                t == null -> "$id: not found"
                !infinitive.matches(t.text) -> "$id: not an infinitive: ${t.text}"
                else -> null
            }
        })
    }
}
