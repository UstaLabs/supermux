package com.swithun.cmpmermaid.core.gitgraph

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidGitGraphOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.SceneLinearGradient
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneShapePaint
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.gitgraph.upstream.mermaid.GitGraphBranch
import com.swithun.cmpmermaid.core.gitgraph.upstream.mermaid.GitGraphCommit
import com.swithun.cmpmermaid.core.gitgraph.upstream.mermaid.GitGraphCommitType
import com.swithun.cmpmermaid.core.gitgraph.upstream.mermaid.GitGraphDb
import com.swithun.cmpmermaid.core.gitgraph.upstream.mermaid.GitGraphDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Native translation of Mermaid 12.0.0 gitGraphRenderer.ts.
 */
internal class GitGraphLayout {
    fun layout(
        db: GitGraphDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val edgeCount = db.getCommitsArray().sumOf { commit -> commit.parents.size }
        if (edgeCount > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "Git Graph edges",
                    actual = edgeCount,
                    maximum = context.options.maxEdges,
                ),
            )
        }
        if (context.options.gitGraph.mainBranchName.isBlank()) {
            return configurationError("Git Graph mainBranchName must not be blank")
        }

        val renderer = Renderer(
            db = db,
            context = context,
        )
        return renderer.build()
    }

    private class Renderer(
        private val db: GitGraphDb,
        private val context: MermaidRenderContext,
    ) {
        private val options = context.options.gitGraph
        private val direction = db.getDirection()
        private val themeName = context.options.themeName ?: DEFAULT_NATIVE_THEME
        private val useReduxGeometry = themeName in REDUX_GEOMETRY_THEMES
        private val useColorTheme = themeName in COLOR_THEMES
        private val useNeoTheme = themeName in NEO_THEMES
        private val useNeoColorGeneration = themeName in NEO_COLOR_GENERATION_THEMES
        private val darkTheme = themeName in DARK_THEMES
        private val branchPositions = linkedMapOf<String, BranchPosition>()
        private val commitPositions = linkedMapOf<String, CommitPosition>()
        private val branchMetrics = linkedMapOf<String, TextMetrics>()
        private val lanes = mutableListOf<Float>()
        private var maxPosition = 0f

        fun build(): GMResult<MermaidScene, MermaidError> {
            val branches = db.getBranchesAsArray()
            val commits = db.getCommits()
            measureAndPositionBranches(branches)
            when (val positioned = positionCommits(commits)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return positioned
            }

            val elements = mutableListOf<SceneElement>()
            if (options.showBranches) {
                drawBranches(branches, elements)
            }
            when (val arrows = drawArrows(commits, elements)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return arrows
            }
            drawCommits(commits, elements)

            val graphBounds = calculateBounds(elements)
            val title = db.diagramTitle
            if (!title.isNullOrEmpty()) {
                // Mermaid: src/utils.ts -> insertTitle
                val metrics = measure(title, TITLE_FONT_SIZE)
                elements += SceneText(
                    text = title,
                    bounds = centeredBounds(
                        centerX = graphBounds.center.x,
                        centerY = -options.titleTopMargin - metrics.height / 2f,
                        metrics = metrics,
                    ),
                    color = context.theme.gitGraph.textColor,
                    fontSize = TITLE_FONT_SIZE,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    zIndex = 30,
                    softWrap = false,
                    horizontalScale = SVG_TEXT_HORIZONTAL_SCALE,
                )
            }

            val contentBounds = calculateBounds(elements)
            val shiftX = options.diagramPadding - contentBounds.left
            val shiftY = options.diagramPadding - contentBounds.top
            val shifted = elements
                .map { element -> element.translate(shiftX, shiftY) }
                .sortedBy(SceneElement::zIndex)
            return GMResult.Ok(
                MermaidScene(
                    width = max(MIN_SCENE_SIZE, contentBounds.width + options.diagramPadding * 2f),
                    height = max(MIN_SCENE_SIZE, contentBounds.height + options.diagramPadding * 2f),
                    background = context.theme.background,
                    elements = shifted,
                    title = title,
                    accessibilityTitle = db.accessibilityTitle,
                    accessibilityDescription = db.accessibilityDescription,
                ),
            )
        }

        private fun measureAndPositionBranches(branches: List<GitGraphBranch>) {
            // Mermaid: gitGraphRenderer.ts -> setBranchPosition
            var position = 0f
            branches.forEachIndexed { index, branch ->
                val metrics = measure(branch.name.toLabelText(), context.theme.fontSize)
                branchMetrics[branch.name] = metrics
                branchPositions[branch.name] = BranchPosition(position, index)
                position += BRANCH_STEP +
                    if (options.rotateCommitLabel) ROTATED_LABEL_LANE else 0f
                if (direction != GitGraphDirection.LR) {
                    position += metrics.width / 2f
                }
            }
        }

        private fun positionCommits(
            commits: Map<String, GitGraphCommit>,
        ): GMResult<Unit, MermaidError> {
            // Mermaid: gitGraphRenderer.ts -> drawCommits(..., modifyGraph = false)
            var position = if (direction == GitGraphDirection.LR) 0f else DEFAULT_POSITION
            var sorted = commits.values.sortedBy(GitGraphCommit::seq)
            if (direction == GitGraphDirection.BT) {
                if (options.parallelCommits) {
                    when (val result = setParallelBottomToTopPositions(sorted)) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return result
                    }
                }
                sorted = sorted.asReversed()
            }

            sorted.forEach { commit ->
                if (options.parallelCommits) {
                    position = when (
                        val result = calculatePosition(commit)
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                }
                val commitPosition = when (val result = getCommitPosition(commit, position)) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                commitPositions[commit.id] = when (direction) {
                    GitGraphDirection.LR ->
                        CommitPosition(commitPosition.positionWithOffset, commitPosition.y)
                    GitGraphDirection.TB,
                    GitGraphDirection.BT,
                    ->
                        CommitPosition(commitPosition.x, commitPosition.positionWithOffset)
                }
                position += if (
                    direction == GitGraphDirection.BT && options.parallelCommits
                ) {
                    COMMIT_STEP
                } else {
                    COMMIT_STEP + LAYOUT_OFFSET
                }
                maxPosition = max(maxPosition, position)
            }
            return GMResult.Ok(Unit)
        }

        private fun setParallelBottomToTopPositions(
            commits: List<GitGraphCommit>,
        ): GMResult<Unit, MermaidError> {
            // Mermaid: gitGraphRenderer.ts -> setParallelBTPos
            var currentPosition = DEFAULT_POSITION
            var maximumPosition = DEFAULT_POSITION
            val roots = mutableListOf<GitGraphCommit>()
            commits.forEach { commit ->
                if (commit.parents.isNotEmpty()) {
                    currentPosition = when (val result = calculateCommitPosition(commit)) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    maximumPosition = max(currentPosition, maximumPosition)
                } else {
                    roots += commit
                }
                when (val result = setCommitPosition(commit, currentPosition)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
            }
            currentPosition = maximumPosition
            roots.forEach { commit ->
                when (val result = setRootPosition(commit, currentPosition)) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
            }
            commits.forEach { commit ->
                if (commit.parents.isNotEmpty()) {
                    val closestParent = findClosestParentBottomToTop(commit.parents)
                        ?: return layoutError(
                            "Closest parent not found for commit ${commit.id}",
                        )
                    val parentPosition = commitPositions[closestParent]
                        ?: return layoutError(
                            "Closest parent position not found for commit ${commit.id}",
                        )
                    currentPosition = parentPosition.y - COMMIT_STEP
                    if (currentPosition <= maximumPosition) {
                        maximumPosition = currentPosition
                    }
                    val branch = branchPositions[commit.branch]
                        ?: return layoutError("Branch not found for commit ${commit.id}")
                    commitPositions[commit.id] = CommitPosition(
                        x = branch.position,
                        y = currentPosition - LAYOUT_OFFSET,
                    )
                }
            }
            return GMResult.Ok(Unit)
        }

        private fun calculateCommitPosition(
            commit: GitGraphCommit,
        ): GMResult<Float, MermaidError> {
            val closestParent = findClosestParent(commit.parents)
                ?: return layoutError("Closest parent not found for commit ${commit.id}")
            val parentPosition = commitPositions[closestParent]?.y
                ?: return layoutError(
                    "Closest parent position not found for commit ${commit.id}",
                )
            return GMResult.Ok(parentPosition + COMMIT_STEP)
        }

        private fun setCommitPosition(
            commit: GitGraphCommit,
            currentPosition: Float,
        ): GMResult<CommitPosition, MermaidError> {
            val branch = branchPositions[commit.branch]
                ?: return layoutError("Branch not found for commit ${commit.id}")
            val position = CommitPosition(
                x = branch.position,
                y = currentPosition + LAYOUT_OFFSET,
            )
            commitPositions[commit.id] = position
            return GMResult.Ok(position)
        }

        private fun setRootPosition(
            commit: GitGraphCommit,
            currentPosition: Float,
        ): GMResult<Unit, MermaidError> {
            val branch = branchPositions[commit.branch]
                ?: return layoutError("Branch not found for commit ${commit.id}")
            commitPositions[commit.id] = CommitPosition(
                x = branch.position,
                y = currentPosition + DEFAULT_POSITION,
            )
            return GMResult.Ok(Unit)
        }

        private fun calculatePosition(
            commit: GitGraphCommit,
        ): GMResult<Float, MermaidError> {
            if (commit.parents.isEmpty()) {
                return when (direction) {
                    GitGraphDirection.TB -> GMResult.Ok(DEFAULT_POSITION)
                    GitGraphDirection.BT -> {
                        val current = commitPositions[commit.id]
                            ?: return layoutError(
                                "Commit position not found for commit ${commit.id}",
                            )
                        GMResult.Ok(current.y - COMMIT_STEP)
                    }
                    GitGraphDirection.LR -> GMResult.Ok(0f)
                }
            }
            val closestParent = findClosestParent(commit.parents)
                ?: return layoutError("Closest parent not found for commit ${commit.id}")
            val parent = commitPositions[closestParent]
                ?: return layoutError(
                    "Closest parent position not found for commit ${commit.id}",
                )
            return when (direction) {
                GitGraphDirection.TB -> GMResult.Ok(parent.y + COMMIT_STEP)
                GitGraphDirection.BT -> {
                    val current = commitPositions[commit.id]
                        ?: return layoutError(
                            "Commit position not found for commit ${commit.id}",
                        )
                    GMResult.Ok(current.y - COMMIT_STEP)
                }
                GitGraphDirection.LR -> GMResult.Ok(parent.x + COMMIT_STEP)
            }
        }

        private fun getCommitPosition(
            commit: GitGraphCommit,
            position: Float,
        ): GMResult<CommitPositionOffset, MermaidError> {
            // Mermaid: gitGraphRenderer.ts -> getCommitPosition
            val positionWithOffset = if (
                direction == GitGraphDirection.BT && options.parallelCommits
            ) {
                position
            } else {
                position + LAYOUT_OFFSET
            }
            val branch = branchPositions[commit.branch]
                ?: return layoutError("Position was undefined for commit ${commit.id}")
            val x = if (direction == GitGraphDirection.LR) {
                positionWithOffset
            } else {
                branch.position
            }
            val y = if (direction == GitGraphDirection.LR) {
                branch.position + if (useReduxGeometry) {
                    REDUX_BRANCH_LABEL_PADDING_Y / 2f + 1f
                } else {
                    -2f
                }
            } else {
                positionWithOffset
            }
            return GMResult.Ok(CommitPositionOffset(x, y, positionWithOffset, position))
        }

        private fun findClosestParent(parents: List<String>): String? {
            var closest: String? = null
            var targetPosition = if (direction == GitGraphDirection.BT) {
                Float.POSITIVE_INFINITY
            } else {
                0f
            }
            parents.forEach { parent ->
                val position = commitPositions[parent] ?: return@forEach
                val candidate = if (direction == GitGraphDirection.LR) position.x else position.y
                val closer = if (direction == GitGraphDirection.BT) {
                    candidate <= targetPosition
                } else {
                    candidate >= targetPosition
                }
                if (closer) {
                    closest = parent
                    targetPosition = candidate
                }
            }
            return closest
        }

        private fun findClosestParentBottomToTop(parents: List<String>): String? {
            var closest: String? = null
            var maximumPosition = Float.POSITIVE_INFINITY
            parents.forEach { parent ->
                val position = commitPositions[parent]?.y ?: return@forEach
                if (position <= maximumPosition) {
                    closest = parent
                    maximumPosition = position
                }
            }
            return closest
        }

        private fun drawBranches(
            branches: List<GitGraphBranch>,
            elements: MutableList<SceneElement>,
        ) {
            // Mermaid: gitGraphRenderer.ts -> drawBranches
            branches.forEachIndexed { index, branch ->
                val position = branchPositions.getValue(branch.name).position
                val spine = branchSpine(position)
                elements += ScenePath(
                    id = "git-branch-${branch.name}",
                    points = listOf(spine.first, spine.second),
                    commands = listOf(
                        ScenePathCommand.MoveTo(spine.first),
                        ScenePathCommand.LineTo(spine.second),
                    ),
                    color = context.theme.gitGraph.commitLineColor,
                    strokeWidth = context.theme.strokeWidth,
                    strokePattern = SceneStrokePattern.Dotted,
                    dashIntervals = listOf(2f, 2f),
                    look = context.options.look,
                    animated = false,
                    zIndex = 1,
                )
                lanes += if (direction == GitGraphDirection.LR) {
                    spine.first.y
                } else {
                    position
                }
                drawBranchLabel(branch, index, position, elements)
            }
        }

        private fun branchSpine(position: Float): Pair<ScenePoint, ScenePoint> {
            val spinePosition = if (direction == GitGraphDirection.LR && useReduxGeometry) {
                position + REDUX_BRANCH_LABEL_PADDING_Y / 2f + 1f
            } else if (direction == GitGraphDirection.LR) {
                position - 2f
            } else {
                position
            }
            return when (direction) {
                GitGraphDirection.LR ->
                    ScenePoint(0f, spinePosition) to ScenePoint(maxPosition, spinePosition)
                GitGraphDirection.TB ->
                    ScenePoint(position, DEFAULT_POSITION) to ScenePoint(position, maxPosition)
                GitGraphDirection.BT ->
                    ScenePoint(position, maxPosition) to ScenePoint(position, DEFAULT_POSITION)
            }
        }

        private fun drawBranchLabel(
            branch: GitGraphBranch,
            rawIndex: Int,
            position: Float,
            elements: MutableList<SceneElement>,
        ) {
            val metrics = branchMetrics.getValue(branch.name)
            val paddingX = if (useReduxGeometry) 16f else 0f
            val paddingY = if (useReduxGeometry) REDUX_BRANCH_LABEL_PADDING_Y else 0f
            val rotateOffset = if (options.rotateCommitLabel) 30f else 0f
            val bounds = when (direction) {
                GitGraphDirection.LR -> {
                    val spineY = branchSpine(position).first.y
                    SceneRect(
                        left = -metrics.width - 23f - rotateOffset,
                        top = spineY - metrics.height / 2f - 2f - paddingY / 2f,
                        right = -5f - rotateOffset + paddingX,
                        bottom = spineY + metrics.height / 2f + 2f + paddingY / 2f,
                    )
                }
                GitGraphDirection.TB -> {
                    val translateX = if (useReduxGeometry) -paddingX / 2f - 3f else 0f
                    val translateY = if (useReduxGeometry) -paddingY - 10f else 0f
                    SceneRect(
                        left = position - metrics.width / 2f - 10f + translateX,
                        top = translateY,
                        right = position + metrics.width / 2f + 8f + paddingX + translateX,
                        bottom = metrics.height + 4f + paddingY + translateY,
                    )
                }
                GitGraphDirection.BT -> {
                    val translateX = if (useReduxGeometry) -paddingX / 2f - 3f else 0f
                    val translateY = if (useReduxGeometry) paddingY + 10f else 0f
                    SceneRect(
                        left = position - metrics.width / 2f - 10f + translateX,
                        top = maxPosition + translateY,
                        right = position + metrics.width / 2f + 8f + paddingX + translateX,
                        bottom = maxPosition + metrics.height + 4f + paddingY + translateY,
                    )
                }
            }
            val textBounds = when (direction) {
                GitGraphDirection.LR -> SceneRect(
                    left = -metrics.width - 14f - rotateOffset + paddingX / 2f,
                    top = branchSpine(position).first.y - metrics.height / 2f - 2f,
                    right = -14f - rotateOffset + paddingX / 2f,
                    bottom = branchSpine(position).first.y + metrics.height / 2f - 2f,
                )
                GitGraphDirection.TB -> {
                    val top = if (useReduxGeometry) -paddingY * 2f + 7f else 0f
                    SceneRect(
                        left = position - metrics.width / 2f - 5f,
                        top = top,
                        right = position + metrics.width / 2f - 5f,
                        bottom = top + metrics.height,
                    )
                }
                GitGraphDirection.BT -> {
                    val top = if (useReduxGeometry) {
                        maxPosition + paddingY * 2f + 4f
                    } else {
                        maxPosition
                    }
                    SceneRect(
                        left = position - metrics.width / 2f - 5f,
                        top = top,
                        right = position + metrics.width / 2f - 5f,
                        bottom = top + metrics.height,
                    )
                }
            }
            val colorIndex = branchColorIndex(rawIndex, forBranchLabel = true)
            elements += SceneShape(
                id = "git-branch-label-background-${branch.name}",
                bounds = bounds,
                kind = if (useReduxGeometry) {
                    SceneShapeKind.Rectangle
                } else {
                    SceneShapeKind.RoundedRectangle
                },
                fill = branchLabelBackground(colorIndex),
                stroke = branchLabelStroke(colorIndex),
                strokeWidth = context.theme.strokeWidth,
                cornerRadius = if (useReduxGeometry) 0f else 4f,
                shadow = if (context.options.look == "neo") context.theme.dropShadow else null,
                strokeGradient = branchLabelStrokeGradient(),
                zIndex = 8,
            )
            elements += SceneText(
                text = branch.name.toLabelText(),
                bounds = textBounds,
                color = branchLabelTextColor(colorIndex),
                fontSize = context.theme.fontSize,
                fontFamily = context.theme.fontFamily,
                weight = if (useReduxGeometry) SceneTextWeight.Bold else SceneTextWeight.Normal,
                zIndex = 9,
                softWrap = false,
                horizontalScale = SVG_TEXT_HORIZONTAL_SCALE,
            )
        }

        private fun drawArrows(
            commits: Map<String, GitGraphCommit>,
            elements: MutableList<SceneElement>,
        ): GMResult<Unit, MermaidError> {
            // Mermaid: gitGraphRenderer.ts -> drawArrows / drawArrow
            commits.values.forEach { commit ->
                commit.parents.forEach { parentId ->
                    val parent = commits[parentId]
                        ?: return layoutError(
                            "Git Graph commit '${commit.id}' references missing parent '$parentId'",
                        )
                    val path = when (val result = arrowPath(parent, commit, commits)) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    elements += path
                }
            }
            return GMResult.Ok(Unit)
        }

        private fun arrowPath(
            parent: GitGraphCommit,
            child: GitGraphCommit,
            commits: Map<String, GitGraphCommit>,
        ): GMResult<ScenePath, MermaidError> {
            val start = commitPositions[parent.id]
                ?: return layoutError("Commit position not found for ${parent.id}")
            val end = commitPositions[child.id]
                ?: return layoutError("Commit position not found for ${child.id}")
            val reroute = shouldRerouteArrow(parent, child, start, end, commits)
            val commands = if (reroute) {
                reroutedArrowCommands(start, end)
            } else {
                directArrowCommands(
                    start = start,
                    end = end,
                    secondaryMergeParent =
                        child.type == GitGraphCommitType.Merge &&
                            parent.id != child.parents.firstOrNull(),
                )
            }
            var branchIndex = branchPositions[child.branch]?.index
                ?: return layoutError("Branch not found for commit ${child.id}")
            if (
                child.type == GitGraphCommitType.Merge &&
                parent.id != child.parents.firstOrNull()
            ) {
                branchIndex = branchPositions[parent.branch]?.index
                    ?: return layoutError("Branch not found for commit ${parent.id}")
            }
            if (
                reroute &&
                (
                    direction == GitGraphDirection.LR && start.y > end.y ||
                        direction != GitGraphDirection.LR && start.x > end.x
                    )
            ) {
                branchIndex = branchPositions[parent.branch]?.index
                    ?: return layoutError("Branch not found for commit ${parent.id}")
            }
            return GMResult.Ok(
                ScenePath(
                    id = "git-arrow-${parent.id}-${child.id}",
                    points = commands.mapNotNull(ScenePathCommand::point),
                    commands = commands,
                    color = commitColor(branchIndex),
                    strokeWidth = if (useReduxGeometry) context.theme.strokeWidth else 8f,
                    look = context.options.look,
                    animated = false,
                    zIndex = 5,
                ),
            )
        }

        private fun shouldRerouteArrow(
            parent: GitGraphCommit,
            child: GitGraphCommit,
            start: CommitPosition,
            end: CommitPosition,
            commits: Map<String, GitGraphCommit>,
        ): Boolean {
            val childIsFurthest = if (direction == GitGraphDirection.LR) {
                start.y < end.y
            } else {
                start.x < end.x
            }
            val branch = if (childIsFurthest) child.branch else parent.branch
            return commits.values.any { candidate ->
                candidate.seq > parent.seq &&
                    candidate.seq < child.seq &&
                    candidate.branch == branch
            }
        }

        private fun reroutedArrowCommands(
            start: CommitPosition,
            end: CommitPosition,
        ): List<ScenePathCommand> {
            val radius = 10f
            val builder = PathCommands(start.point)
            if (direction == GitGraphDirection.TB || direction == GitGraphDirection.BT) {
                val laneX = findLane(min(start.x, end.x), max(start.x, end.x))
                val verticalOffset = if (direction == GitGraphDirection.TB) radius else -radius
                if (start.x < end.x) {
                    builder.lineTo(laneX - radius, start.y)
                    builder.cornerTo(laneX, start.y, laneX, start.y + verticalOffset)
                    builder.lineTo(laneX, end.y - verticalOffset)
                    builder.cornerTo(laneX, end.y, laneX + radius, end.y)
                } else {
                    builder.lineTo(laneX + radius, start.y)
                    builder.cornerTo(laneX, start.y, laneX, start.y + verticalOffset)
                    builder.lineTo(laneX, end.y - verticalOffset)
                    builder.cornerTo(laneX, end.y, laneX - radius, end.y)
                }
            } else {
                val laneY = findLane(min(start.y, end.y), max(start.y, end.y))
                if (start.y < end.y) {
                    builder.lineTo(start.x, laneY - radius)
                    builder.cornerTo(start.x, laneY, start.x + radius, laneY)
                    builder.lineTo(end.x - radius, laneY)
                    builder.cornerTo(end.x, laneY, end.x, laneY + radius)
                } else {
                    builder.lineTo(start.x, laneY + radius)
                    builder.cornerTo(start.x, laneY, start.x + radius, laneY)
                    builder.lineTo(end.x - radius, laneY)
                    builder.cornerTo(end.x, laneY, end.x, laneY - radius)
                }
            }
            builder.lineTo(end.x, end.y)
            return builder.commands
        }

        private fun directArrowCommands(
            start: CommitPosition,
            end: CommitPosition,
            secondaryMergeParent: Boolean,
        ): List<ScenePathCommand> {
            if (
                (direction == GitGraphDirection.LR && start.y == end.y) ||
                (direction != GitGraphDirection.LR && start.x == end.x)
            ) {
                return listOf(
                    ScenePathCommand.MoveTo(start.point),
                    ScenePathCommand.LineTo(end.point),
                )
            }

            val radius = 20f
            val builder = PathCommands(start.point)
            when (direction) {
                GitGraphDirection.TB -> {
                    val horizontalDirection = if (start.x < end.x) 1f else -1f
                    if (secondaryMergeParent) {
                        builder.lineTo(start.x, end.y - radius)
                        builder.cornerTo(
                            start.x,
                            end.y,
                            start.x + horizontalDirection * radius,
                            end.y,
                        )
                    } else {
                        builder.lineTo(end.x - horizontalDirection * radius, start.y)
                        builder.cornerTo(
                            end.x,
                            start.y,
                            end.x,
                            start.y + radius,
                        )
                    }
                }
                GitGraphDirection.BT -> {
                    val horizontalDirection = if (start.x < end.x) 1f else -1f
                    if (secondaryMergeParent) {
                        builder.lineTo(start.x, end.y + radius)
                        builder.cornerTo(
                            start.x,
                            end.y,
                            start.x + horizontalDirection * radius,
                            end.y,
                        )
                    } else {
                        builder.lineTo(end.x - horizontalDirection * radius, start.y)
                        builder.cornerTo(
                            end.x,
                            start.y,
                            end.x,
                            start.y - radius,
                        )
                    }
                }
                GitGraphDirection.LR -> {
                    val verticalDirection = if (start.y < end.y) 1f else -1f
                    if (secondaryMergeParent) {
                        builder.lineTo(end.x - radius, start.y)
                        builder.cornerTo(
                            end.x,
                            start.y,
                            end.x,
                            start.y + verticalDirection * radius,
                        )
                    } else {
                        builder.lineTo(start.x, end.y - verticalDirection * radius)
                        builder.cornerTo(
                            start.x,
                            end.y,
                            start.x + radius,
                            end.y,
                        )
                    }
                }
            }
            builder.lineTo(end.x, end.y)
            return builder.commands
        }

        private fun findLane(
            first: Float,
            second: Float,
            depth: Int = 0,
        ): Float {
            val candidate = first + abs(first - second) / 2f
            if (depth > 5) return candidate
            if (lanes.all { lane -> abs(lane - candidate) >= 10f }) {
                lanes += candidate
                return candidate
            }
            val difference = abs(first - second)
            return findLane(first, second - difference / 5f, depth + 1)
        }

        private fun drawCommits(
            commits: Map<String, GitGraphCommit>,
            elements: MutableList<SceneElement>,
        ) {
            // Mermaid: gitGraphRenderer.ts -> drawCommits(..., modifyGraph = true)
            // Its shared gLabels group paints each commit's label and tags in commit order.
            // Later opaque backgrounds intentionally cover earlier overlapping text.
            val sorted = commits.values.sortedBy(GitGraphCommit::seq)
            val paintOrder = if (direction == GitGraphDirection.BT) {
                sorted.asReversed()
            } else {
                sorted
            }
            paintOrder.forEach { commit ->
                val position = commitPositions.getValue(commit.id)
                val branchIndex = branchPositions.getValue(commit.branch).index
                drawCommitBullet(commit, position, branchIndex, elements)
                drawCommitLabel(commit, position, elements)
                drawCommitTags(commit, position, elements)
            }
        }

        private fun drawCommitBullet(
            commit: GitGraphCommit,
            position: CommitPosition,
            branchIndex: Int,
            elements: MutableList<SceneElement>,
        ) {
            // Mermaid: gitGraphRenderer.ts -> drawCommitBullet
            val symbolType = commit.customType ?: commit.type
            val radius = if (useReduxGeometry) 7f else 10f
            val branchColor = commitColor(branchIndex)
            when (symbolType) {
                GitGraphCommitType.Highlight -> {
                    val outerSize = if (useReduxGeometry) 14f else 20f
                    val innerSize = if (useReduxGeometry) 8f else 12f
                    elements += rectangle(
                        id = "git-commit-${commit.id}-highlight-outer",
                        center = position.point,
                        width = outerSize,
                        height = outerSize,
                        fill = highlightColor(branchIndex),
                        stroke = highlightColor(branchIndex),
                        strokeWidth = context.theme.strokeWidth,
                        zIndex = 11,
                    )
                    elements += rectangle(
                        id = "git-commit-${commit.id}-highlight-inner",
                        center = position.point,
                        width = innerSize,
                        height = innerSize,
                        fill = specialInnerColor(),
                        stroke = specialInnerColor(),
                        strokeWidth = context.theme.strokeWidth,
                        zIndex = 12,
                    )
                }
                GitGraphCommitType.CherryPick -> {
                    elements += circle(
                        id = "git-commit-${commit.id}",
                        center = position.point,
                        radius = radius,
                        fill = cherryPickBackgroundColor(),
                        stroke = cherryPickBackgroundColor(),
                        zIndex = 11,
                    )
                    val fruitColor = if (darkTheme) BLACK else WHITE
                    listOf(-3f, 3f).forEachIndexed { index, offset ->
                        elements += circle(
                            id = "git-commit-${commit.id}-cherry-$index",
                            center = ScenePoint(position.x + offset, position.y + 2f),
                            radius = if (useReduxGeometry) 2.5f else 2.75f,
                            fill = fruitColor,
                            stroke = fruitColor,
                            zIndex = 12,
                        )
                        elements += line(
                            id = "git-commit-${commit.id}-stem-$index",
                            start = ScenePoint(position.x + offset, position.y + 1f),
                            end = ScenePoint(position.x, position.y - 5f),
                            color = fruitColor,
                            width = 1f,
                            zIndex = 13,
                        )
                    }
                }
                else -> {
                    elements += circle(
                        id = "git-commit-${commit.id}",
                        center = position.point,
                        radius = radius,
                        fill = branchColor,
                        stroke = branchColor,
                        zIndex = 11,
                    )
                    if (symbolType == GitGraphCommitType.Merge) {
                        elements += circle(
                            id = "git-commit-${commit.id}-merge-inner",
                            center = position.point,
                            radius = if (useReduxGeometry) 5f else 6f,
                            fill = specialInnerColor(),
                            stroke = specialInnerColor(),
                            zIndex = 12,
                        )
                    }
                    if (symbolType == GitGraphCommitType.Reverse) {
                        val arm = if (useReduxGeometry) 4f else 5f
                        val crossColor = specialInnerColor()
                        elements += ScenePath(
                            id = "git-commit-${commit.id}-reverse",
                            points = listOf(
                                ScenePoint(position.x - arm, position.y - arm),
                                ScenePoint(position.x + arm, position.y + arm),
                                ScenePoint(position.x - arm, position.y + arm),
                                ScenePoint(position.x + arm, position.y - arm),
                            ),
                            commands = listOf(
                                ScenePathCommand.MoveTo(
                                    ScenePoint(position.x - arm, position.y - arm),
                                ),
                                ScenePathCommand.LineTo(
                                    ScenePoint(position.x + arm, position.y + arm),
                                ),
                                ScenePathCommand.MoveTo(
                                    ScenePoint(position.x - arm, position.y + arm),
                                ),
                                ScenePathCommand.LineTo(
                                    ScenePoint(position.x + arm, position.y - arm),
                                ),
                            ),
                            color = crossColor,
                            strokeWidth = if (useNeoColorGeneration) {
                                context.theme.strokeWidth
                            } else {
                                3f
                            },
                            look = context.options.look,
                            animated = false,
                            zIndex = 13,
                        )
                    }
                }
            }
        }

        private fun drawCommitLabel(
            commit: GitGraphCommit,
            position: CommitPosition,
            elements: MutableList<SceneElement>,
        ) {
            // Mermaid: gitGraphRenderer.ts -> drawCommitLabel
            val visible = commit.type != GitGraphCommitType.CherryPick &&
                (
                    (commit.customId && commit.type == GitGraphCommitType.Merge) ||
                        commit.type != GitGraphCommitType.Merge
                    ) &&
                options.showCommitLabel
            if (!visible) return

            val metrics = measure(commit.id, context.theme.gitGraph.commitLabelFontSize)
            var textBounds = if (direction == GitGraphDirection.LR) {
                SceneRect(
                    left = position.x - metrics.width / 2f,
                    top = position.y + 13.5f,
                    right = position.x + metrics.width / 2f,
                    bottom = position.y + 13.5f + metrics.height,
                )
            } else {
                SceneRect(
                    left = position.x - metrics.width - 4f * PX,
                    top = position.y - 12f,
                    right = position.x - 4f * PX,
                    bottom = position.y - 12f + metrics.height,
                )
            }
            var background = if (direction == GitGraphDirection.LR) {
                textBounds.inflate(PY, PY)
            } else {
                SceneRect(
                    left = position.x - (metrics.width + 4f * PX + 5f),
                    top = position.y - 12f,
                    right = position.x - (4f * PX + 1f),
                    bottom = position.y - 8f + metrics.height,
                )
            }
            val rotation = if (options.rotateCommitLabel) -45f else 0f
            var pivot = position.point
            if (options.rotateCommitLabel && direction == GitGraphDirection.LR) {
                val sourcePosition = position.x - LAYOUT_OFFSET
                val translateX = -7.5f - ((metrics.width + 10f) / 25f) * 9.5f
                val translateY = 10f + (metrics.width / 25f) * 8.5f
                textBounds = textBounds.translate(translateX, translateY)
                background = background.translate(translateX, translateY)
                pivot = ScenePoint(
                    x = sourcePosition + translateX,
                    y = position.y + translateY,
                )
            }
            elements += polygon(
                id = "git-commit-label-background-${commit.id}",
                points = rotatedRectPoints(background, rotation, pivot),
                fill = if (useNeoColorGeneration) {
                    TRANSPARENT
                } else {
                    context.theme.gitGraph.commitLabelBackground.withOpacity(0.5f)
                },
                stroke = TRANSPARENT,
                strokeWidth = 0f,
                zIndex = COMMIT_LABEL_LAYER,
            )
            elements += SceneText(
                text = commit.id,
                bounds = textBounds,
                color = if (useNeoColorGeneration) {
                    context.theme.gitGraph.nodeBorder
                } else {
                    context.theme.gitGraph.commitLabelColor
                },
                fontSize = context.theme.gitGraph.commitLabelFontSize,
                fontFamily = context.theme.fontFamily,
                weight = if (useReduxGeometry) SceneTextWeight.Bold else SceneTextWeight.Normal,
                rotationDegrees = rotation,
                rotationPivot = pivot,
                zIndex = COMMIT_LABEL_LAYER,
                softWrap = false,
                horizontalScale = SVG_TEXT_HORIZONTAL_SCALE,
            )
        }

        private fun drawCommitTags(
            commit: GitGraphCommit,
            position: CommitPosition,
            elements: MutableList<SceneElement>,
        ) {
            // Mermaid: gitGraphRenderer.ts -> drawCommitTags
            if (commit.tags.isEmpty()) return
            val measured = commit.tags.asReversed().map { tag ->
                tag to measure(tag, context.theme.gitGraph.tagLabelFontSize)
            }
            val maximumWidth = measured.maxOf { (_, metrics) -> metrics.width }
            val maximumHeight = measured.maxOf { (_, metrics) -> metrics.height }
            measured.forEachIndexed { index, (tag, metrics) ->
                val offset = index * TAG_STEP
                if (direction == GitGraphDirection.LR) {
                    val centerY = position.y - 19.2f - offset
                    val left = position.x - maximumWidth / 2f - PX
                    val right = position.x + maximumWidth / 2f + PX
                    val halfHeight = maximumHeight / 2f + PY
                    val tipX = position.x - LAYOUT_OFFSET - maximumWidth / 2f - PX / 2f
                    val points = listOf(
                        ScenePoint(tipX, centerY + PY),
                        ScenePoint(tipX, centerY - PY),
                        ScenePoint(left, centerY - halfHeight),
                        ScenePoint(right, centerY - halfHeight),
                        ScenePoint(right, centerY + halfHeight),
                        ScenePoint(left, centerY + halfHeight),
                    )
                    elements += tagShape(commit, index, points)
                    elements += circle(
                        id = "git-tag-hole-${commit.id}-$index",
                        center = ScenePoint(
                            position.x - LAYOUT_OFFSET - maximumWidth / 2f + PX / 2f,
                            centerY,
                        ),
                        radius = 1.5f,
                        fill = context.theme.gitGraph.tagLabelColor,
                        stroke = context.theme.gitGraph.tagLabelColor,
                        zIndex = COMMIT_LABEL_LAYER,
                    )
                    elements += SceneText(
                        text = tag,
                        bounds = centeredBounds(
                            centerX = position.x,
                            centerY = position.y - 16f - offset - metrics.height / 2f,
                            metrics = metrics,
                        ),
                        color = context.theme.gitGraph.tagLabelColor,
                        fontSize = context.theme.gitGraph.tagLabelFontSize,
                        fontFamily = context.theme.fontFamily,
                        weight = SceneTextWeight.Normal,
                        zIndex = COMMIT_LABEL_LAYER,
                        softWrap = false,
                        horizontalScale = SVG_TEXT_HORIZONTAL_SCALE,
                    )
                } else {
                    drawVerticalTag(
                        commit = commit,
                        index = index,
                        tag = tag,
                        metrics = metrics,
                        maximumWidth = maximumWidth,
                        maximumHeight = maximumHeight,
                        position = position,
                        offset = offset,
                        elements = elements,
                    )
                }
            }
        }

        private fun drawVerticalTag(
            commit: GitGraphCommit,
            index: Int,
            tag: String,
            metrics: TextMetrics,
            maximumWidth: Float,
            maximumHeight: Float,
            position: CommitPosition,
            offset: Float,
            elements: MutableList<SceneElement>,
        ) {
            val originY = position.y - LAYOUT_OFFSET + offset
            val halfHeight = maximumHeight / 2f
            val pivot = ScenePoint(position.x, position.y - LAYOUT_OFFSET)
            val polygonPoints = listOf(
                ScenePoint(position.x, originY + 2f),
                ScenePoint(position.x, originY - 2f),
                ScenePoint(position.x + LAYOUT_OFFSET, originY - halfHeight - 2f),
                ScenePoint(
                    position.x + LAYOUT_OFFSET + maximumWidth + 4f,
                    originY - halfHeight - 2f,
                ),
                ScenePoint(
                    position.x + LAYOUT_OFFSET + maximumWidth + 4f,
                    originY + halfHeight + 2f,
                ),
                ScenePoint(position.x + LAYOUT_OFFSET, originY + halfHeight + 2f),
            ).map { point ->
                rotate(point, pivot, 45f).translate(12f, 12f)
            }
            elements += tagShape(commit, index, polygonPoints)
            val holeCenter = rotate(
                ScenePoint(position.x + PX / 2f, originY),
                pivot,
                45f,
            ).translate(12f, 12f)
            elements += circle(
                id = "git-tag-hole-${commit.id}-$index",
                center = holeCenter,
                radius = 1.5f,
                fill = context.theme.gitGraph.tagLabelColor,
                stroke = context.theme.gitGraph.tagLabelColor,
                zIndex = COMMIT_LABEL_LAYER,
            )
            val textCenter = ScenePoint(
                position.x + 5f + metrics.width / 2f,
                originY + 3f - metrics.height / 2f,
            ).translate(14f, 14f)
            elements += SceneText(
                text = tag,
                bounds = centeredBounds(textCenter.x, textCenter.y, metrics),
                color = context.theme.gitGraph.tagLabelColor,
                fontSize = context.theme.gitGraph.tagLabelFontSize,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                rotationDegrees = 45f,
                rotationPivot = pivot.translate(14f, 14f),
                zIndex = COMMIT_LABEL_LAYER,
                softWrap = false,
                horizontalScale = SVG_TEXT_HORIZONTAL_SCALE,
            )
        }

        private fun tagShape(
            commit: GitGraphCommit,
            index: Int,
            points: List<ScenePoint>,
        ): SceneShape = polygon(
            id = "git-tag-background-${commit.id}-$index",
            points = points,
            fill = if (useNeoColorGeneration) {
                context.theme.gitGraph.mainBackground
            } else {
                context.theme.gitGraph.tagLabelBackground
            },
            stroke = if (useNeoColorGeneration) {
                context.theme.gitGraph.nodeBorder
            } else {
                context.theme.gitGraph.tagLabelBorder
            },
            strokeWidth = context.theme.strokeWidth,
            shadow = if (useNeoColorGeneration) context.theme.dropShadow else null,
            zIndex = COMMIT_LABEL_LAYER,
        )

        private fun branchColorIndex(
            rawIndex: Int,
            forBranchLabel: Boolean = false,
        ): Int {
            val limit = if (forBranchLabel && useReduxGeometry) {
                max(THEME_COLOR_LIMIT, context.theme.borderColorArray.size)
            } else {
                THEME_COLOR_LIMIT
            }
            return if (useColorTheme && rawIndex > 0 && limit > 1) {
                ((rawIndex - 1) % (limit - 1)) + 1
            } else {
                rawIndex % limit
            }
        }

        private fun commitColor(rawIndex: Int): SceneColor {
            val index = branchColorIndex(rawIndex)
            if (!useNeoColorGeneration) {
                return context.theme.gitGraph.colors[index % context.theme.gitGraph.colors.size]
            }
            if (useNeoTheme && index > 0) {
                return context.theme.gitGraph.colors[index % context.theme.gitGraph.colors.size]
            }
            if (!useColorTheme || index == 0) {
                return context.theme.gitGraph.nodeBorder
            }
            return context.theme.borderColorArray.getOrNull(index)
                ?: context.theme.gitGraph.colors[index % context.theme.gitGraph.colors.size]
        }

        private fun highlightColor(rawIndex: Int): SceneColor {
            val index = branchColorIndex(rawIndex)
            if (!useNeoColorGeneration) {
                return context.theme.gitGraph.inverseColors[
                    index % context.theme.gitGraph.inverseColors.size
                ]
            }
            if (useNeoTheme && index > 0) {
                return context.theme.gitGraph.inverseColors[
                    index % context.theme.gitGraph.inverseColors.size
                ]
            }
            if (!useColorTheme) {
                return context.theme.gitGraph.nodeBorder
            }
            if (index == 0) {
                return context.theme.gitGraph.nodeBorder
            }
            return context.theme.borderColorArray.getOrNull(index)
                ?: context.theme.gitGraph.inverseColors[
                    index % context.theme.gitGraph.inverseColors.size
                ]
        }

        private fun specialInnerColor(): SceneColor =
            if (useNeoColorGeneration) {
                context.theme.gitGraph.mainBackground
            } else {
                context.theme.gitGraph.primaryColor
            }

        private fun cherryPickBackgroundColor(): SceneColor =
            // Mermaid: styles.js -> inherited .commit-bullets fill
            if (useNeoColorGeneration) {
                context.theme.gitGraph.nodeBorder
            } else {
                context.theme.gitGraph.textColor
            }

        private fun branchLabelBackground(index: Int): SceneColor {
            if (!useNeoColorGeneration) {
                return context.theme.gitGraph.colors[index % context.theme.gitGraph.colors.size]
            }
            if (useNeoTheme) {
                return context.theme.gitGraph.mainBackground
            }
            if (!useColorTheme || index == 0 || darkTheme) {
                return context.theme.gitGraph.mainBackground
            }
            return context.theme.borderColorArray.getOrNull(index)
                ?: context.theme.gitGraph.mainBackground
        }

        private fun branchLabelStroke(index: Int): SceneColor =
            if (useNeoColorGeneration) {
                if (useColorTheme && index > 0) {
                    context.theme.borderColorArray.getOrNull(index)
                        ?: context.theme.gitGraph.nodeBorder
                } else {
                    context.theme.gitGraph.nodeBorder
                }
            } else {
                TRANSPARENT
            }

        private fun branchLabelStrokeGradient(): SceneLinearGradient? =
            // Mermaid: styles.js -> genGitGraphGradient
            if (useNeoTheme && context.theme.gitGraph.useGradient) {
                SceneLinearGradient(
                    startColor = context.theme.gitGraph.gradientStart,
                    endColor = context.theme.gitGraph.gradientStop,
                )
            } else {
                null
            }

        private fun branchLabelTextColor(index: Int): SceneColor =
            if (useNeoTheme && index > 0) {
                context.theme.gitGraph.branchLabelColors[
                    index % context.theme.gitGraph.branchLabelColors.size
                ]
            } else if (useNeoColorGeneration) {
                context.theme.gitGraph.nodeBorder
            } else {
                context.theme.gitGraph.branchLabelColors[
                    index % context.theme.gitGraph.branchLabelColors.size
                ]
            }

        private fun measure(
            text: String,
            fontSize: Float,
        ): TextMetrics = context.textMetrics.measure(
            TextMetricsRequest(
                text = text,
                fontSize = fontSize,
                maxWidth = MAX_TEXT_WIDTH,
                lineHeight = 1.2f,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                // Mermaid: gitGraphRenderer.ts measures native SVG <text> without scaling.
                horizontalScale = SVG_TEXT_HORIZONTAL_SCALE,
            ),
        )

        private fun <T> layoutError(message: String): GMResult<T, MermaidError> =
            GMResult.Err(MermaidError.Layout(message))
    }

    private data class BranchPosition(
        val position: Float,
        val index: Int,
    )

    private data class CommitPosition(
        val x: Float,
        val y: Float,
    ) {
        val point: ScenePoint get() = ScenePoint(x, y)
    }

    private data class CommitPositionOffset(
        val x: Float,
        val y: Float,
        val positionWithOffset: Float,
        val sourcePosition: Float,
    )

    private class PathCommands(
        start: ScenePoint,
    ) {
        val commands = mutableListOf<ScenePathCommand>(ScenePathCommand.MoveTo(start))
        private var currentPoint = start

        fun lineTo(
            x: Float,
            y: Float,
        ) {
            currentPoint = ScenePoint(x, y)
            commands += ScenePathCommand.LineTo(currentPoint)
        }

        fun cornerTo(
            controlX: Float,
            controlY: Float,
            endX: Float,
            endY: Float,
        ) {
            val corner = ScenePoint(controlX, controlY)
            val end = ScenePoint(endX, endY)
            commands += ScenePathCommand.CubicTo(
                control1 = ScenePoint(
                    x = currentPoint.x +
                        (corner.x - currentPoint.x) * QUARTER_ARC_KAPPA,
                    y = currentPoint.y +
                        (corner.y - currentPoint.y) * QUARTER_ARC_KAPPA,
                ),
                control2 = ScenePoint(
                    x = end.x + (corner.x - end.x) * QUARTER_ARC_KAPPA,
                    y = end.y + (corner.y - end.y) * QUARTER_ARC_KAPPA,
                ),
                end = end,
            )
            currentPoint = end
        }
    }

    private companion object {
        const val LAYOUT_OFFSET = 10f
        const val COMMIT_STEP = 40f
        const val PX = 4f
        const val PY = 2f
        const val THEME_COLOR_LIMIT = 8
        const val DEFAULT_POSITION = 30f
        const val BRANCH_STEP = 50f
        const val ROTATED_LABEL_LANE = 40f
        const val REDUX_BRANCH_LABEL_PADDING_Y = 12f
        const val TAG_STEP = 20f
        const val TITLE_FONT_SIZE = 18f
        const val COMMIT_LABEL_LAYER = 18
        const val SVG_TEXT_HORIZONTAL_SCALE = 1f
        const val QUARTER_ARC_KAPPA = 0.5522848f
        const val MAX_TEXT_WIDTH = 100_000f
        const val MIN_SCENE_SIZE = 1f
        const val DEFAULT_NATIVE_THEME = "redux-color"

        val REDUX_GEOMETRY_THEMES = setOf(
            "redux",
            "redux-dark",
            "redux-color",
            "redux-dark-color",
        )
        val COLOR_THEMES = setOf("redux-color", "redux-dark-color")
        val NEO_THEMES = setOf("neo", "neo-dark")
        val DARK_THEMES = setOf("dark", "redux-dark", "redux-dark-color", "neo-dark")
        val NEO_COLOR_GENERATION_THEMES = setOf(
            "redux",
            "redux-dark",
            "redux-color",
            "redux-dark-color",
            "neo",
            "neo-dark",
        )
        val TRANSPARENT = SceneColor(0x00000000)
        val WHITE = SceneColor(0xFFFFFFFF)
        val BLACK = SceneColor(0xFF000000)
    }
}

private fun String.toLabelText(): String =
    replace(Regex("""\\n|<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .lineSequence()
        .joinToString("\n", transform = String::trim)

private fun centeredBounds(
    centerX: Float,
    centerY: Float,
    metrics: TextMetrics,
): SceneRect = SceneRect(
    left = centerX - metrics.width / 2f,
    top = centerY - metrics.height / 2f,
    right = centerX + metrics.width / 2f,
    bottom = centerY + metrics.height / 2f,
)

private fun rectangle(
    id: String,
    center: ScenePoint,
    width: Float,
    height: Float,
    fill: SceneColor,
    stroke: SceneColor,
    strokeWidth: Float,
    zIndex: Int,
): SceneShape = SceneShape(
    id = id,
    bounds = SceneRect(
        left = center.x - width / 2f,
        top = center.y - height / 2f,
        right = center.x + width / 2f,
        bottom = center.y + height / 2f,
    ),
    kind = SceneShapeKind.Rectangle,
    fill = fill,
    stroke = stroke,
    strokeWidth = strokeWidth,
    cornerRadius = 0f,
    zIndex = zIndex,
)

private fun circle(
    id: String,
    center: ScenePoint,
    radius: Float,
    fill: SceneColor,
    stroke: SceneColor,
    zIndex: Int,
): SceneShape = SceneShape(
    id = id,
    bounds = SceneRect(
        left = center.x - radius,
        top = center.y - radius,
        right = center.x + radius,
        bottom = center.y + radius,
    ),
    kind = SceneShapeKind.Circle,
    fill = fill,
    stroke = stroke,
    strokeWidth = 1f,
    zIndex = zIndex,
)

private fun line(
    id: String,
    start: ScenePoint,
    end: ScenePoint,
    color: SceneColor,
    width: Float,
    zIndex: Int,
): ScenePath = ScenePath(
    id = id,
    points = listOf(start, end),
    commands = listOf(
        ScenePathCommand.MoveTo(start),
        ScenePathCommand.LineTo(end),
    ),
    color = color,
    strokeWidth = width,
    look = "classic",
    animated = false,
    zIndex = zIndex,
)

private fun polygon(
    id: String,
    points: List<ScenePoint>,
    fill: SceneColor,
    stroke: SceneColor,
    strokeWidth: Float,
    shadow: com.swithun.cmpmermaid.core.SceneShadow? = null,
    zIndex: Int,
): SceneShape {
    val bounds = points.bounds()
    val center = bounds.center
    return SceneShape(
        id = id,
        bounds = bounds,
        kind = SceneShapeKind.Rectangle,
        geometry = SceneShapeGeometry(
            paths = listOf(
                SceneShapePath(
                    points = points.map { point ->
                        ScenePoint(point.x - center.x, point.y - center.y)
                    },
                    fill = SceneShapePaint.Fill,
                    stroke = if (strokeWidth > 0f) {
                        SceneShapePaint.Stroke
                    } else {
                        SceneShapePaint.None
                    },
                ),
            ),
            outline = points.map { point ->
                ScenePoint(point.x - center.x, point.y - center.y)
            },
        ),
        fill = fill,
        stroke = stroke,
        strokeWidth = strokeWidth,
        cornerRadius = 0f,
        shadow = shadow,
        zIndex = zIndex,
    )
}

private fun rotatedRectPoints(
    bounds: SceneRect,
    degrees: Float,
    pivot: ScenePoint,
): List<ScenePoint> {
    val points = listOf(
        ScenePoint(bounds.left, bounds.top),
        ScenePoint(bounds.right, bounds.top),
        ScenePoint(bounds.right, bounds.bottom),
        ScenePoint(bounds.left, bounds.bottom),
    )
    return if (degrees == 0f) points else points.map { point -> rotate(point, pivot, degrees) }
}

private fun rotate(
    point: ScenePoint,
    pivot: ScenePoint,
    degrees: Float,
): ScenePoint {
    val radians = degrees * kotlin.math.PI.toFloat() / 180f
    val cosine = cos(radians)
    val sine = sin(radians)
    val dx = point.x - pivot.x
    val dy = point.y - pivot.y
    return ScenePoint(
        x = pivot.x + dx * cosine - dy * sine,
        y = pivot.y + dx * sine + dy * cosine,
    )
}

private fun ScenePoint.translate(
    dx: Float,
    dy: Float,
): ScenePoint = ScenePoint(x + dx, y + dy)

private fun SceneColor.withOpacity(opacity: Float): SceneColor {
    val alpha = ((argb ushr 24) and 0xFF).toInt()
    val adjusted = (alpha * opacity.coerceIn(0f, 1f)).toInt()
    return SceneColor((argb and 0x00FFFFFF) or (adjusted.toLong() shl 24))
}

private fun ScenePathCommand.point(): ScenePoint? = when (this) {
    is ScenePathCommand.MoveTo -> point
    is ScenePathCommand.LineTo -> point
    is ScenePathCommand.QuadraticTo -> end
    is ScenePathCommand.CubicTo -> end
    is ScenePathCommand.ArcTo -> end
}

private fun List<ScenePoint>.bounds(): SceneRect {
    if (isEmpty()) return SceneRect(0f, 0f, 0f, 0f)
    return SceneRect(
        left = minOf(ScenePoint::x),
        top = minOf(ScenePoint::y),
        right = maxOf(ScenePoint::x),
        bottom = maxOf(ScenePoint::y),
    )
}

private fun calculateBounds(elements: List<SceneElement>): SceneRect {
    val bounds = elements.mapNotNull { element ->
        when (element) {
            is SceneShape -> element.bounds
            is SceneText -> rotatedRectPoints(
                bounds = element.bounds,
                degrees = element.rotationDegrees,
                pivot = element.rotationPivot ?: element.bounds.center,
            ).bounds()
            is ScenePath -> element.commands.flatMap { command ->
                when (command) {
                    is ScenePathCommand.MoveTo -> listOf(command.point)
                    is ScenePathCommand.LineTo -> listOf(command.point)
                    is ScenePathCommand.QuadraticTo -> listOf(command.control, command.end)
                    is ScenePathCommand.CubicTo ->
                        listOf(command.control1, command.control2, command.end)
                    is ScenePathCommand.ArcTo -> listOf(command.end)
                }
            }.bounds()
            is SceneAsset -> element.bounds
        }
    }
    return bounds.reduceOrNull(SceneRect::union) ?: SceneRect(0f, 0f, 0f, 0f)
}

private fun SceneElement.translate(
    dx: Float,
    dy: Float,
): SceneElement = when (this) {
    is SceneShape -> copy(bounds = bounds.translate(dx, dy))
    is SceneText -> copy(
        bounds = bounds.translate(dx, dy),
        rotationPivot = rotationPivot?.translate(dx, dy),
    )
    is ScenePath -> copy(
        points = points.map { point -> point.translate(dx, dy) },
        commands = commands.map { command -> command.translate(dx, dy) },
    )
    is SceneAsset -> copy(bounds = bounds.translate(dx, dy))
}

private fun ScenePathCommand.translate(
    dx: Float,
    dy: Float,
): ScenePathCommand = when (this) {
    is ScenePathCommand.MoveTo -> copy(point = point.translate(dx, dy))
    is ScenePathCommand.LineTo -> copy(point = point.translate(dx, dy))
    is ScenePathCommand.QuadraticTo -> copy(
        control = control.translate(dx, dy),
        end = end.translate(dx, dy),
    )
    is ScenePathCommand.CubicTo -> copy(
        control1 = control1.translate(dx, dy),
        control2 = control2.translate(dx, dy),
        end = end.translate(dx, dy),
    )
    is ScenePathCommand.ArcTo -> copy(end = end.translate(dx, dy))
}

private fun <T> configurationError(message: String): GMResult<T, MermaidError> =
    GMResult.Err(MermaidError.Configuration(message))
