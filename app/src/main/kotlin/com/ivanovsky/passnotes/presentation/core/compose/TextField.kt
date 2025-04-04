package com.ivanovsky.passnotes.presentation.core.compose

import android.graphics.Typeface.MONOSPACE
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import com.ivanovsky.passnotes.R
import com.ivanovsky.passnotes.presentation.core.compose.model.InputType
import com.ivanovsky.passnotes.util.StringUtils.EMPTY
import com.ivanovsky.passnotes.util.isDigitsOnly

@Composable
fun AppTextField(
    value: String,
    label: String,
    error: String? = null,
    inputType: InputType,
    maxLength: Int = Int.MAX_VALUE,
    onValueChange: (String) -> Unit,
    isPasswordToggleEnabled: Boolean = false,
    isPasswordVisible: Boolean = false,
    onPasswordToggleClicked: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isError = (error != null)

    OutlinedTextField(
        textStyle = PrimaryTextStyle(),
        value = value,
        label = {
            Text(text = label)
        },
        visualTransformation = if (isPasswordToggleEnabled && !isPasswordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        isError = isError,
        supportingText = {
            if (isError) {
                Text(
                    text = error ?: EMPTY,
                    style = SecondaryTextStyle(),
                    color = AppTheme.theme.colors.errorText
                )
            }
        },
        keyboardOptions = buildKeyboardOptions(inputType),
        onValueChange = { newValue ->
            if (inputType == InputType.NUMBER && !newValue.isDigitsOnly()) {
                return@OutlinedTextField
            }

            if (newValue.length > maxLength) {
                return@OutlinedTextField
            }

            onValueChange.invoke(newValue)
        },
        trailingIcon = {
            when {
                isError -> {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_info_24dp),
                        contentDescription = null,
                        tint = AppTheme.theme.colors.errorText
                    )
                }

                isPasswordToggleEnabled -> {
                    val iconResourceId = if (isPasswordVisible) {
                        R.drawable.ic_visibility_on_24dp
                    } else {
                        R.drawable.ic_visibility_off_24dp
                    }

                    Icon(
                        painter = painterResource(iconResourceId),
                        contentDescription = null,
                        tint = AppTheme.theme.colors.primaryIcon,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = rememberRipple(bounded = false)
                            ) {
                                onPasswordToggleClicked?.invoke()
                            }
                            .padding(dimensionResource(R.dimen.half_margin))
                    )
                }
            }
        },
        colors = AppTextFieldColors(),
        modifier = modifier
    )
}

private fun buildKeyboardOptions(inputType: InputType): KeyboardOptions {
    return when (inputType) {
        InputType.TEXT -> KeyboardOptions.Default
        InputType.NUMBER -> KeyboardOptions(keyboardType = KeyboardType.Number)
    }
}

@Composable
fun AppTextFieldColors(): TextFieldColors {
    return TextFieldDefaults.colors().copy(
        unfocusedIndicatorColor = AppTheme.theme.colors.unfocusedColor,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        unfocusedLabelColor = AppTheme.theme.colors.hint,
        disabledContainerColor = Color.Transparent,
        disabledTextColor = AppTheme.theme.colors.unfocusedColor,
        disabledLabelColor = AppTheme.theme.colors.unfocusedColor
    )
}

@Composable
fun PrimaryTextStyle(
    color: Color = AppTheme.theme.colors.primaryText
): TextStyle {
    return TextStyle(
        fontSize = AppTheme.theme.textMetrics.primary,
        fontWeight = FontWeight.Normal,
        color = color
    )
}

@Composable
fun SecondaryTextStyle(
    fontSize: TextUnit = AppTheme.theme.textMetrics.primary,
    color: Color = AppTheme.theme.colors.secondaryText
): TextStyle {
    return TextStyle(
        fontSize = fontSize,
        fontWeight = FontWeight.Normal,
        color = color
    )
}

@Composable
fun PrimaryMonospaceTextStyle(
    color: Color = AppTheme.theme.colors.primaryText
): TextStyle {
    return TextStyle(
        fontSize = AppTheme.theme.textMetrics.primary,
        color = color,
        fontFamily = FontFamily(Typeface(MONOSPACE))
    )
}

@Composable
fun SecondaryMonospaceTextStyle(
    color: Color = AppTheme.theme.colors.secondaryText
): TextStyle {
    return TextStyle(
        fontSize = AppTheme.theme.textMetrics.secondary,
        color = color,
        fontFamily = FontFamily(Typeface(MONOSPACE))
    )
}

@Composable
fun HeaderTextStyle(): TextStyle {
    return TextStyle(
        fontSize = AppTheme.theme.textMetrics.header,
        fontWeight = FontWeight.Normal,
        color = AppTheme.theme.colors.primaryText
    )
}

@Composable
fun ErrorTextStyle(
    isBold: Boolean = true,
    fontSize: TextUnit = AppTheme.theme.textMetrics.header
): TextStyle {
    return TextStyle(
        fontSize = fontSize,
        fontWeight = if (isBold) FontWeight.Bold else FontWeight.Normal,
        color = AppTheme.theme.colors.errorText
    )
}