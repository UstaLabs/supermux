package dev.supermux.ui
import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertTrue
class MarkdownBlocksTest {
  @Test fun splits_prose_and_code() {
    val b = parseMarkdownBlocks("before\n```kotlin\nval x = 1\n```\nafter")
    assertEquals(3, b.size)
    assertTrue(b[0] is MdBlock.Prose && (b[0] as MdBlock.Prose).text.trim()=="before")
    assertTrue(b[1] is MdBlock.Code && (b[1] as MdBlock.Code).code.trim()=="val x = 1" && (b[1] as MdBlock.Code).lang=="kotlin")
    assertTrue(b[2] is MdBlock.Prose && (b[2] as MdBlock.Prose).text.trim()=="after")
  }
  @Test fun no_fence_is_one_prose() {
    val b = parseMarkdownBlocks("just text **bold**"); assertEquals(1, b.size); assertTrue(b[0] is MdBlock.Prose)
  }
  @Test fun unterminated_fence_is_code() {
    val b = parseMarkdownBlocks("text\n```\ncode line"); assertEquals(2, b.size); assertTrue(b[1] is MdBlock.Code)
  }
}
