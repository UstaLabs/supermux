package dev.supermux.chat

import dev.supermux.proto.GitLiteStatusDto

/** Whether the git menu's third row is Publish (no upstream yet) rather than Push. Mirrors Android's
 *  `session.git?.unpublished == true` gate. Pure so the decision is unit-testable off the DTO. */
fun shouldPublish(git: GitLiteStatusDto?): Boolean = git?.unpublished == true
