// Skill table ported from Modu `lib/service/ai/readany_skills.dart` and
// `reading_skill_registry.dart` (derived from ReadAny, GPL-3.0-or-later).
// Prompts live in res/values*/ai_skills.xml.
package com.zenread.data.ai.skills

import androidx.annotation.StringRes
import com.zenread.data.ai.R

/** What book text a skill needs attached to its prompt. */
enum class SkillContext {
    /** The current chapter (or the selection, when there is one). */
    CHAPTER,

    /** An outline of the whole book from the index, plus the current chapter as fallback. */
    BOOK,

    /** The selected passage only. */
    SELECTION,
}

/**
 * A preset prompt. [id]s are stable (upstream persisted them) and are stored with
 * messages, so never rename one.
 */
data class ReadingSkill(
    val id: String,
    @param:StringRes val name: Int,
    @param:StringRes val description: Int,
    @param:StringRes val prompt: Int,
    val context: SkillContext,
)

object ReadingSkills {
    /** Chips in the AI panel, in upstream order. */
    val all: List<ReadingSkill> = listOf(
        skill("smart_summary", R.string.ai_skill_smart_summary_name, R.string.ai_skill_smart_summary_description, R.string.ai_skill_smart_summary_prompt, SkillContext.CHAPTER),
        skill("book_summary", R.string.ai_skill_book_summary_name, R.string.ai_skill_book_summary_description, R.string.ai_skill_book_summary_prompt, SkillContext.BOOK),
        skill("concept_explainer", R.string.ai_skill_concept_explainer_name, R.string.ai_skill_concept_explainer_description, R.string.ai_skill_concept_explainer_prompt, SkillContext.CHAPTER),
        skill("argument_analyzer", R.string.ai_skill_argument_analyzer_name, R.string.ai_skill_argument_analyzer_description, R.string.ai_skill_argument_analyzer_prompt, SkillContext.CHAPTER),
        skill("character_tracker", R.string.ai_skill_character_tracker_name, R.string.ai_skill_character_tracker_description, R.string.ai_skill_character_tracker_prompt, SkillContext.CHAPTER),
        skill("quote_collector", R.string.ai_skill_quote_collector_name, R.string.ai_skill_quote_collector_description, R.string.ai_skill_quote_collector_prompt, SkillContext.CHAPTER),
        skill("reading_guide", R.string.ai_skill_reading_guide_name, R.string.ai_skill_reading_guide_description, R.string.ai_skill_reading_guide_prompt, SkillContext.CHAPTER),
        skill("smart_translator", R.string.ai_skill_smart_translator_name, R.string.ai_skill_smart_translator_description, R.string.ai_skill_smart_translator_prompt, SkillContext.CHAPTER),
        skill("vocabulary_helper", R.string.ai_skill_vocabulary_helper_name, R.string.ai_skill_vocabulary_helper_description, R.string.ai_skill_vocabulary_helper_prompt, SkillContext.CHAPTER),
        skill("mindmap", R.string.ai_skill_mindmap_name, R.string.ai_skill_mindmap_description, R.string.ai_skill_mindmap_prompt, SkillContext.CHAPTER),
    )

    /** Quick actions offered when the panel opens with a selection. */
    val selectionActions: List<ReadingSkill> = listOf(
        skill("explain", R.string.ai_skill_action_explain_name, R.string.ai_skill_action_explain_description, R.string.ai_skill_action_explain_prompt, SkillContext.SELECTION),
        skill("translate", R.string.ai_skill_action_translate_name, R.string.ai_skill_action_translate_description, R.string.ai_skill_smart_translator_prompt, SkillContext.SELECTION),
        skill("summarize", R.string.ai_skill_action_summarize_name, R.string.ai_skill_action_summarize_description, R.string.ai_skill_action_summarize_prompt, SkillContext.SELECTION),
    )

    fun byId(id: String): ReadingSkill? = (all + selectionActions).firstOrNull { it.id == id }

    private fun skill(id: String, name: Int, description: Int, prompt: Int, context: SkillContext) =
        ReadingSkill(id, name, description, prompt, context)
}
