package com.guanyi.mirra.domain

import com.guanyi.mirra.data.local.entity.NoteSemanticType

class RuleBasedNoteTypeSuggester {
    fun suggest(content: String): NoteSemanticType {
        val normalized = content.trim()
        return when {
            normalized.endsWith('?') || normalized.endsWith('？') -> NoteSemanticType.QUESTION
            normalized.startsWith("总结：") || normalized.startsWith("总结:") -> NoteSemanticType.SUMMARY
            else -> NoteSemanticType.UNDERSTANDING
        }
    }
}
