package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/zenuml.md.
 */
internal data class MermaidZenUmlDocCase(
    val id: String,
    val source: String,
)

internal val officialZenUmlDocumentationCases: List<MermaidZenUmlDocCase> = listOf(
    MermaidZenUmlDocCase(
        "001_demo",
        """
        zenuml
            title Demo
            Alice->John: Hello John, how are you?
            John->Alice: Great!
            Alice->John: See you later!
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "002_declared_participants",
        """
        zenuml
            title Declare participant (optional)
            Bob
            Alice
            Alice->Bob: Hi Bob
            Bob->Alice: Hi Alice
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "003_annotators",
        """
        zenuml
            title Annotators
            @Actor Alice
            @Database Bob
            Alice->Bob: Hi Bob
            Bob->Alice: Hi Alice
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "004_aliases",
        """
        zenuml
            title Aliases
            A as Alice
            J as John
            A->J: Hello John, how are you?
            J->A: Great!
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "005_sync_messages",
        """
        zenuml
            title Sync message
            A.SyncMessage
            A.SyncMessage(with, parameters) {
              B.nestedSyncMessage()
            }
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "006_async_message",
        """
        zenuml
            title Async message
            Alice->Bob: How are you?
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "007_creation",
        """
        zenuml
            new A1
            new A2(with, parameters)
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "008_reply_forms",
        """
        zenuml
            // 1. assign a variable from a sync message.
            a = A.SyncMessage()

            // 1.1. optionally give the variable a type
            SomeType a = A.SyncMessage()

            // 2. use return keyword
            A.SyncMessage() {
            return result
            }

            // 3. use @return or @reply annotator on an async message
            @return
            A->B: result
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "009_nested_reply",
        """
        zenuml
            title Reply message
            Client->A.method() {
              B.method() {
                if(condition) {
                  return x1
                  // return early
                  @return
                  A->Client: x11
                }
              }
              return x2
            }
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "010_nesting",
        """
        zenuml
            A.method() {
              B.nested_sync_method()
              B->C: nested async message
            }
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "011_comments",
        """
        zenuml
            // a comment on a participant will not be rendered
            BookService
            // a comment on a message.
            // **Markdown** is supported.
            BookService.getBook()
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "012_loop",
        """
        zenuml
            Alice->John: Hello John, how are you?
            while(true) {
              John->Alice: Great!
            }
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "013_alt",
        """
        zenuml
            Alice->Bob: Hello Bob, how are you?
            if(is_sick) {
              Bob->Alice: Not so good :(
            } else {
              Bob->Alice: Feeling fresh like a daisy
            }
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "014_opt",
        """
        zenuml
            Alice->Bob: Hello Bob, how are you?
            Bob->Alice: Not so good :(
            opt {
              Bob->Alice: Thanks for asking
            }
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "015_parallel",
        """
        zenuml
            par {
                Alice->Bob: Hello guys!
                Alice->John: Hello guys!
            }
        """.trimIndent(),
    ),
    MermaidZenUmlDocCase(
        "016_try_catch_finally",
        """
        zenuml
            try {
              Consumer->API: Book something
              API->BookingService: Start booking process
            } catch {
              API->Consumer: show failure
            } finally {
              API->BookingService: rollback status
            }
        """.trimIndent(),
    ),
)
