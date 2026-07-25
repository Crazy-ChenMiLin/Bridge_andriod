package xyz.chenmilin.ankimcpbridge.anki

import org.junit.Assert.*
import org.junit.Test

class MapNoteFieldsTest {

    @Test
    fun `严格匹配按笔记类型顺序输出`() {
        val ordered = listOf("题目", "核心思路", "复杂度", "Java代码", "易错点", "来源")
        val provided = mapOf(
            "题目" to "两数之和",
            "Java代码" to "class Solution {}",
            "核心思路" to "HashMap",
            "复杂度" to "O(n)",
            "易错点" to "注意下标",
            "来源" to "LeetCode"
        )
        val result = mapNoteFields(ordered, provided)
        assertArrayEquals(
            arrayOf("两数之和", "HashMap", "O(n)", "class Solution {}", "注意下标", "LeetCode"),
            result
        )
    }

    @Test
    fun `Map 输入顺序不同不影响实际字段顺序`() {
        val ordered = listOf("题目", "核心思路", "复杂度")
        val scrambled = mapOf(
            "复杂度" to "O(n)",
            "核心思路" to "HashMap",
            "题目" to "两数之和"
        )
        val result = mapNoteFields(ordered, scrambled)
        assertArrayEquals(arrayOf("两数之和", "HashMap", "O(n)"), result)
    }

    @Test
    fun `未提供的字段写入空字符串`() {
        val ordered = listOf("Front", "Back", "Extra")
        val provided = mapOf("Front" to "Q", "Back" to "A")
        val result = mapNoteFields(ordered, provided)
        assertArrayEquals(arrayOf("Q", "A", ""), result)
    }

    @Test
    fun `忽略大小写匹配`() {
        val ordered = listOf("Front", "Back")
        val provided = mapOf("front" to "Q", "BACK" to "A")
        val result = mapNoteFields(ordered, provided)
        assertArrayEquals(arrayOf("Q", "A"), result)
    }

    @Test
    fun `忽略大小写歧义抛异常`() {
        // 笔记类型字段为 Question；输入中出现两个仅大小写不同的键（question / QUESTION），
        // 它们忽略大小写后都匹配 Question，触发歧义。
        val ordered = listOf("Question")
        val provided = mapOf("question" to "Q", "QUESTION" to "A")
        try {
            mapNoteFields(ordered, provided)
            fail("expected FieldMappingException")
        } catch (e: FieldMappingException) {
            assertEquals(AnkiErrors.AMBIGUOUS_FIELD, e.code)
        }
    }

    @Test
    fun `未知字段抛异常`() {
        val ordered = listOf("Front", "Back")
        val provided = mapOf("Front" to "Q", "Back" to "A", "Side" to "X")
        try {
            mapNoteFields(ordered, provided)
            fail("expected FieldMappingException")
        } catch (e: FieldMappingException) {
            assertEquals(AnkiErrors.FIELD_NOT_FOUND, e.code)
            assertTrue(e.message!!.contains("Side"))
        }
    }

    @Test
    fun `全部字段为空抛异常`() {
        val ordered = listOf("Front", "Back")
        val provided = mapOf("Front" to "  ", "Back" to "")
        try {
            mapNoteFields(ordered, provided)
            fail("expected FieldMappingException")
        } catch (e: FieldMappingException) {
            assertEquals(AnkiErrors.NO_VALID_FIELD, e.code)
        }
    }

    @Test
    fun `只有一个字段非空即合法`() {
        val ordered = listOf("Front", "Back")
        val provided = mapOf("Front" to "Q", "Back" to "")
        val result = mapNoteFields(ordered, provided)
        assertArrayEquals(arrayOf("Q", ""), result)
    }
}
