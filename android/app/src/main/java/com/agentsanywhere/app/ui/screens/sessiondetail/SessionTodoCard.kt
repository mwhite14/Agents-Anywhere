package com.agentsanywhere.app.ui.screens.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentsanywhere.app.R
import com.agentsanywhere.app.feature.sessiondetail.RuntimeTodo
import com.agentsanywhere.app.feature.sessiondetail.RuntimeTodoStatus
import com.agentsanywhere.app.ui.designsystem.LocalAAColors
import com.agentsanywhere.app.ui.designsystem.noRippleClickable
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronUp
import com.composables.icons.lucide.Circle
import com.composables.icons.lucide.CircleDot
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.Lucide

/**
 * The agent's current whole-list to-do snapshot, pinned above the composer.
 * Collapsed it still summarizes progress, so a running turn is never a blank
 * chat; expanded it lists every item in write order.
 */
@Composable
internal fun SessionTodoCard(
    todos: List<RuntimeTodo>,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    if (todos.isEmpty()) return
    val colors = LocalAAColors.current
    var expanded by rememberSaveable { mutableStateOf(true) }
    val completed = todos.count { it.status == RuntimeTodoStatus.Completed }
    val inProgress = todos.count { it.status == RuntimeTodoStatus.InProgress }
    val pending = todos.count { it.status == RuntimeTodoStatus.Pending }
    val completedLabel = stringResource(R.string.session_todos_completed, completed)
    val inProgressLabel = stringResource(R.string.session_todos_in_progress, inProgress)
    val pendingLabel = stringResource(R.string.session_todos_pending, pending)
    val summary = listOfNotNull(
        completedLabel.takeIf { completed > 0 },
        inProgressLabel.takeIf { inProgress > 0 },
        pendingLabel.takeIf { pending > 0 },
    ).joinToString(" · ")
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp)
            .clip(shape)
            .background(colors.raisedSurface)
            .border(1.dp, colors.border, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .noRippleClickable { expanded = !expanded }
                .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = if (expanded) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Lucide.ListTodo,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.session_todos_title),
                color = colors.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = summary,
                color = colors.muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                contentDescription = stringResource(
                    if (expanded) R.string.session_todos_collapse else R.string.session_todos_expand,
                ),
                tint = colors.muted,
                modifier = Modifier.size(17.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 184.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 16.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                todos.forEach { todo ->
                    Row(verticalAlignment = Alignment.Top) {
                        TodoStatusIcon(status = todo.status, darkMode = darkMode)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = todo.content,
                            color = if (todo.status == RuntimeTodoStatus.Completed) colors.muted else colors.ink,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoStatusIcon(status: RuntimeTodoStatus, darkMode: Boolean) {
    val colors = LocalAAColors.current
    val tint = when (status) {
        RuntimeTodoStatus.Completed -> if (darkMode) colors.noticeSuccess else colors.sessionStatusAccent
        RuntimeTodoStatus.InProgress -> colors.runtimeTransitioning
        RuntimeTodoStatus.Pending -> colors.faint
    }
    val completed = status == RuntimeTodoStatus.Completed
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .size(16.dp)
            .clip(CircleShape)
            .background(if (completed) tint else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when (status) {
                RuntimeTodoStatus.Completed -> Lucide.Check
                RuntimeTodoStatus.InProgress -> Lucide.CircleDot
                RuntimeTodoStatus.Pending -> Lucide.Circle
            },
            contentDescription = null,
            tint = if (completed) Color.White else tint,
            modifier = Modifier.size(if (completed) 11.dp else 16.dp),
        )
    }
}
