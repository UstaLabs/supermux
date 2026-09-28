package com.swithun.cmpmermaid.core.usecase

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseActorType
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseAnimation
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseBoundaryType
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseDirection
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseParser
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseRelationshipType
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UsecaseParserTest {
    @Test
    fun acceptsUpstreamCanonicalGrammarMatrix() {
        val sources = listOf(
            "usecase-beta",
            "usecase-beta\nactor User",
            "usecase-beta\r\nactor User\r\n",
            "usecase-beta\ractor User\r",
            "usecase-beta\n\n  %% retained\nLogin",
            """
            usecase-beta
            actor Admin("Main Administrator")@{ type: hollow, business: true } <<Human>>:::external
            actor "System Administrator", "`**Automation**`"
            Login(Sign in)@{ business: false } <<Primary>>:::critical
            Report[Generate report]
            "Reset password"
            "`**Markdown** use case`"
            """.trimIndent(),
            """
            usecase-beta
            actor User
            User@{
              business: true
              type: normal,
            }
            """.trimIndent(),
            """
            usecase-beta
            systemBoundary "Authentication System":::system
              %% boundary comment
              actor User, Admin("Administrator")

              Login("Sign in"):::critical
              Report[Generate report]
            end
            User --> Login
            """.trimIndent(),
            """
            usecase-beta
            systemBoundary sb1["Payment service"]@{ type: package }:::system
              Authorize("Authorize payment")
            end
            systemBoundary sb2(Billing)@{
              type: rect
            }:::billing
              Invoice[Create invoice]
            end
            systemBoundary sb3("`**Support**`")
              Refund[Issue refund]
            end
            """.trimIndent(),
            """
            usecase-beta
            direction LR
            systemBoundary Auth
            end
            Login
            Auth@{ type: package }
            note for Login "`Requires an **active session**`"
            json Payload@{
              "fruit": "Apple",
              "nested": { "brace": "}", "items": [1, 2] }
            }:::data
            classDef external,critical fill:#fff,stroke-width:3px
            class Login,Payload external,critical
            style Login fill:#fee,stroke-dasharray:5\,5
            """.trimIndent(),
            """
            usecase-beta
            accTitle: Authentication use cases
            accDescr: Actors and authentication flows
            """.trimIndent(),
            """
            usecase-beta
            accDescr {
              Actors authenticate,
              reset credentials, and sign out.
            }
            """.trimIndent(),
        ) + listOf(
            "A --> B",
            "A <-- B",
            "A -- B",
            "A --o B",
            "A o-- B",
            "A --x B",
            "A x-- B",
            "A -- \"starts session\" --> B",
            "A <-- \"reverse label\" -- B",
            "A -- label -- B",
            "A -- label --o B",
            "A o-- label -- B",
            "A -- label --x B",
            "A x-- label -- B",
            "A ..> : include B",
            "A ..> : INCLUDE B",
            "A ..> : ExTeNd B",
            "A --|> B",
            "A ---> B",
            "A <---- B",
            "A ---- B",
            "A -- longer ----> B",
            "A login@--> B",
            "A dependency@..> : include B",
        ).map { relation -> "usecase-beta\n$relation" }

        sources.forEach { source ->
            val result = UsecaseParser(null, 0).parse(source)
            assertTrue(
                result is GMResult.Ok,
                "Expected upstream grammar to accept:\n$source\nResult: $result",
            )
        }
    }

    @Test
    fun rejectsUpstreamCanonicalGrammarMatrix() {
        val sources = listOf(
            "Usecase\nA",
            "usecase-beta\nactor A actor B",
            "usecase actor A",
            "usecase-beta\nactor A, B --> C",
            "usecase-beta\nunknown command",
            "usecase-beta\n// not a comment",
            "usecase-beta\n# not a comment",
            "usecase-beta\nA; B",
            "usecase-beta\n:User:",
            "usecase-beta\nactor User as U",
            "usecase-beta\nusecase \"Login\" as Login",
            "usecase-beta\nnewpage",
            "usecase-beta\npackage \"Auth\" {",
            "usecase-beta\nrectangle \"Auth\" {",
            "usecase-beta\nskinparam actorStyle awesome",
            "usecase-beta\nallowmixing",
            "usecase-beta\n== Section ==",
            "usecase-beta\nA \\n B",
            "usecase-beta\nA -left-> B",
            "usecase-beta\nnote left of Login \"text\"",
            "usecase-beta\nnote for Login as N \"text\"",
            "usecase-beta\nnote for Login,Report \"text\"",
            "usecase-beta\nsystemBoundary A\nsystemBoundary B\nend\nend",
            "usecase-beta\nsystemBoundary A\nUser --> Login\nend",
            "usecase-beta\nsystemBoundary A\nnote for User \"text\"\nend",
            "usecase-beta\nsystemBoundary A\njson Payload@{}\nend",
            "usecase-beta\nA ---o B",
            "usecase-beta\nA o--- B",
            "usecase-beta\nA ---x B",
            "usecase-beta\nA ---|> B",
            "usecase-beta\nA ...> : include B",
            "usecase-beta\nA <<>>",
            "usecase-beta\nA <<first\nsecond>>",
            "usecase-beta\nA <<primary",
            "usecase-beta\njson Payload@{\"nested\": {",
            "usecase-beta\nA(\"`unfinished\")",
            "usecase-beta\nstyle A fill:red;stroke:blue",
            "usecase-beta\nsystemBoundary A:::system@{ type: package }\nend",
            "usecase-beta\nsystemBoundary A <<S>>\nend",
            "usecase-beta\nsystemBoundary [Payment service]\nend",
        )

        sources.forEach { source ->
            val result = UsecaseParser(null, 0).parse(source)
            assertTrue(
                result is GMResult.Err,
                "Expected upstream grammar to reject:\n$source\nResult: $result",
            )
        }
    }

    @Test
    fun matchesUpstreamDeclarationAndGlobalNamespaceRules() {
        val equivalent = parse(
            """
            usecase-beta
            systemBoundary Auth
            actor User("Person")@{ type: hollow } <<Human>>
            actor User("Person")@{ type: hollow } <<Human>>
            Login[Sign in] <<Primary>>
            Login[Sign in] <<Primary>>
            end
            """.trimIndent(),
        )
        assertEquals(listOf("User", "Login"), equivalent.systemBoundaries.getValue("Auth").members)

        val rejectedBodies = listOf(
            "actor Shared\nShared",
            "Login(Sign in)\nLogin[Sign in]",
            "Login(First label)\nLogin(Second label)",
            "Login <<Primary>>\nLogin <<Secondary>>",
            "systemBoundary First\nLogin\nend\nsystemBoundary Second\nLogin\nend",
            "\"A-B\"\n\"A B\"",
            "\"A B\"\nA_B(Explicit)",
            "actor Shared\njson Shared@{}",
            "actor link\nA link@--> B",
            "A link@--> B\nB link@--> C",
        )
        rejectedBodies.forEach { body ->
            assertRejected("usecase-beta\n$body")
        }
    }

    @Test
    fun matchesUpstreamTargetAndRelationshipValidation() {
        listOf(
            "note for Ghost \"Missing\"",
            "class Ghost important",
            "style Ghost fill:red",
            "Ghost@{ type: package }",
            "actor User\nLogin\nUser --|> Login",
            "actor User\nactor Admin\nUser ..> : include Admin",
            "json Payload@{}\nInspect\nPayload ..> : include Inspect",
            "json Payload@{}\nInspect --o Payload",
            "json Payload@{}\nnote for Payload \"invalid\"",
            "systemBoundary Auth\nLogin\nend\nnote for Auth \"invalid\"",
            "A link@--> B\nnote for link \"invalid\"",
        ).forEach { body ->
            assertRejected("usecase-beta\n$body")
        }
    }

    @Test
    fun validatesBusinessGeometryAndEdgeAnimationMetadata() {
        listOf(
            "actor Icon@{ icon: \"fa:user\", business: true }",
            "actor Awesome@{ type: awesome, business: true }",
            "Report[Generate report]@{ business: true }",
            "actor User@{ type: giant }",
            "actor User@{ fillColor: red }",
            "A link@--> B\nlink@{ animation: medium }",
            "A link@--> B\nlink@{ animate: fast }",
        ).forEach { body ->
            assertRejected("usecase-beta\n$body")
        }

        val document = parse(
            """
            usecase-beta
            A trueEdge@--> B
            trueEdge@{ animate: true }
            A fastEdge@--> B
            fastEdge@{ animation: fast }
            A slowEdge@--> B
            slowEdge@{ animation: slow }
            A offEdge@--> B
            offEdge@{ animate: false }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Triple("trueEdge", true, null),
                Triple("fastEdge", true, UsecaseAnimation.Fast),
                Triple("slowEdge", true, UsecaseAnimation.Slow),
                Triple("offEdge", false, null),
            ),
            document.relationships.map { relationship ->
                Triple(relationship.id, relationship.animated, relationship.animation)
            },
        )
    }

    @Test
    fun supportsUpstreamBoundaryMetadataStylesAndNumericIds() {
        val document = parse(
            """
            usecase-beta
            actor 1("Customer")
            actor 1mg("Milligram")
            2("Place order")
            3rd("Third party")
            json 4 @{"a": 1}
            systemBoundary "Payment service"@{ type: package }:::system
              Authorize("Authorize payment")
            end
            Payment_service@{ type: rect }
            1 --> 2
            1mg --> 3rd
            2 --> 4
            classDef system font-family:Arial Black
            style 2 border:1px solid red,fill:#eee
            """.trimIndent(),
        )

        assertEquals(listOf("1", "1mg"), document.actors.keys.toList())
        assertEquals(listOf("2", "3rd", "Authorize"), document.useCases.keys.toList())
        assertEquals(UsecaseBoundaryType.Rectangle, document.systemBoundaries.getValue("Payment_service").type)
        assertEquals(
            listOf("border:1px solid red", "fill:#eee"),
            document.useCases.getValue("2").styles,
        )
        assertEquals(
            listOf("font-family:Arial Black"),
            document.classDefinitions.getValue("system").styles,
        )
    }

    @Test
    fun resolvesForwardDeclarationsAndDoesNotInferActors() {
        val document = parse(
            """
            usecase-beta
            Customer --> Login
            actor Customer("Customer")
            Login("Sign in")
            """.trimIndent(),
        )

        assertEquals(listOf("Customer"), document.actors.keys.toList())
        assertEquals(listOf("Login"), document.useCases.keys.toList())
        assertEquals("Sign in", document.useCases.getValue("Login").label.text)
    }

    @Test
    fun parsesActorVariantsBusinessAndStereotypes() {
        val document = parse(
            """
            usecase-beta
            actor Normal
            actor Hollow@{ type: hollow, business: true } <<Broker>>
            actor Awesome@{ type: awesome }
            actor Icon@{ icon: "fa:user" }
            Core("Checkout")@{ business: true } <<Primary>>
            """.trimIndent(),
        )

        assertEquals(UsecaseActorType.Normal, document.actors.getValue("Normal").type)
        assertEquals(UsecaseActorType.Hollow, document.actors.getValue("Hollow").type)
        assertTrue(document.actors.getValue("Hollow").business)
        assertEquals("Broker", document.actors.getValue("Hollow").stereotype)
        assertEquals(UsecaseActorType.Awesome, document.actors.getValue("Awesome").type)
        assertEquals(UsecaseActorType.Icon, document.actors.getValue("Icon").type)
        assertEquals("fa:user", document.actors.getValue("Icon").icon)
        assertTrue(document.useCases.getValue("Core").business)
        assertEquals("Primary", document.useCases.getValue("Core").stereotype)
    }

    @Test
    fun parsesBoundaryJsonNotesAndStyles() {
        val document = parse(
            """
            usecase-beta
            systemBoundary ordering["Ordering System"]@{ type: package }:::system
              actor Staff
              Review[Review order]
            end
            json Payload@{
              "2": "second",
              "1": "first",
              "enabled": true
            }:::data
            note for Review "Check policy"
            Review --> Payload
            classDef system stroke:#c8a02a
            class Payload data
            style Review stroke:#c33,stroke-width:4px
            """.trimIndent(),
        )

        val boundary = document.systemBoundaries.getValue("ordering")
        assertEquals(UsecaseBoundaryType.Package, boundary.type)
        assertEquals(listOf("Staff", "Review"), boundary.members)
        assertEquals(UsecaseShape.Rectangle, document.useCases.getValue("Review").shape)
        assertEquals(listOf("2", "1", "enabled"), document.jsonNodes.getValue("Payload").value.keys.toList())
        assertEquals("Review", document.notes.getValue("note-0").target)
        assertEquals(listOf("data"), document.jsonNodes.getValue("Payload").classes)
        assertEquals(listOf("stroke:#c33", "stroke-width:4px"), document.useCases.getValue("Review").styles)
    }

    @Test
    fun parsesRelationshipSemanticsAndDirection() {
        val document = parse(
            """
            usecase-beta
            direction TB
            actor Admin
            actor Person
            Checkout
            Payment
            Admin --|> Person
            Checkout payment@..> : include Payment
            Payment ..> : extend Checkout
            Checkout -- starts flow ---> Payment
            """.trimIndent(),
        )

        assertEquals(UsecaseDirection.TopToBottom, document.direction)
        assertEquals(
            listOf(
                UsecaseRelationshipType.Generalization,
                UsecaseRelationshipType.Include,
                UsecaseRelationshipType.Extend,
                UsecaseRelationshipType.Association,
            ),
            document.relationships.map { it.type },
        )
        assertEquals(2, document.relationships.last().minimumLength)
        assertEquals("starts flow", document.relationships.last().label?.text)
    }

    @Test
    fun supportsMultilineMarkdownAndAccessibilityDescription() {
        val document = parse(
            """
            usecase-beta
            accTitle: Account access
            accDescr {
              A customer signs in.
              Password reset is available.
            }
            Reset("`Reset
            password`")
            """.trimIndent(),
        )

        assertEquals("Account access", document.accessibilityTitle)
        assertTrue(document.accessibilityDescription.orEmpty().contains("Password reset"))
        assertEquals("Reset\npassword", document.useCases.getValue("Reset").label.text)
    }

    @Test
    fun rejectsInvalidRelationshipKinds() {
        val result = UsecaseParser(null, 0).parse(
            """
            usecase-beta
            actor Customer
            Login
            Customer ..> : include Login
            """.trimIndent(),
        )

        assertIs<GMResult.Err<*>>(result)
    }

    @Test
    fun rejectsGlobalIdCollisions() {
        val result = UsecaseParser(null, 0).parse(
            """
            usecase-beta
            actor Shared
            Shared("Use case")
            """.trimIndent(),
        )

        assertIs<GMResult.Err<*>>(result)
    }

    @Test
    fun rejectsInvalidActorMetadataCombination() {
        val result = UsecaseParser(null, 0).parse(
            """
            usecase-beta
            actor Broken@{ icon: "fa:user", type: awesome }
            """.trimIndent(),
        )

        assertIs<GMResult.Err<*>>(result)
    }

    private fun parse(source: String) = when (val result = UsecaseParser(null, 0).parse(source)) {
        is GMResult.Ok -> result.value
        is GMResult.Err -> throw AssertionError(result.error.message)
    }

    private fun assertRejected(source: String) {
        val result = UsecaseParser(null, 0).parse(source)
        assertTrue(result is GMResult.Err, "Expected rejection:\n$source\nResult: $result")
    }
}
