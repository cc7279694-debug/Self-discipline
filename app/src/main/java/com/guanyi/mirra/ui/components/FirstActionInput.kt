package com.guanyi.mirra.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guanyi.mirra.domain.FirstActionResolver

@Composable
fun FirstActionInput(
    value: String,
    onValueChange: (String) -> Unit,
    currentPage: Int,
    enabled: Boolean = true,
) {
    val error = FirstActionResolver.validationError(value).takeIf { value.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("写一个现在就能完成的小动作，例如把书放到桌上，读第一段。不要写“今天读 30 页”等目标。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("第一步动作") },
            isError = error != null,
            supportingText = { Text(error ?: "每次准备开始时会展示这一步，你可以在开始准备前修改。") },
            minLines = 2,
            maxLines = 4,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        if (currentPage > 0) {
            Text("建议：${FirstActionResolver.suggestionFor(currentPage)}", style = MaterialTheme.typography.bodyMedium)
            MirraSecondaryButton(
                onClick = { onValueChange(FirstActionResolver.suggestionFor(currentPage)) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("使用建议动作") }
        }
    }
}
