package com.schedule.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

// ====== 应用内日期/时间选择弹窗（不依赖系统 OEM 对话框） ======
// 视觉对齐系统"日期和时间"设置页：日历网格选日期、双列滚轮选时分，
// 底部灰"取消"+蓝"确定"圆角按钮。滚轮复用提前提醒已验证的 WheelColumn。

// 不能写成文件级 val：那会在类初始化时取一次，换了主题色就不跟着变了
private val PickerBlue: Color get() = AppC.pickerBlue
/** 确定按钮/选中格上的文字色：跟着底色走，浅底自动黑字 */
private fun pickerText(): Color = readableOn(AppC.pickerBlue)

@Composable
private fun PickerButtons(onDismiss: () -> Unit, onConfirm: () -> Unit): Pair<@Composable () -> Unit, @Composable () -> Unit> {
    val dismiss: @Composable () -> Unit = {
        Surface(shape = RoundedCornerShape(22.dp), color = AppC.chipGray, onClick = onDismiss) {
            Text("取消", modifier = Modifier.padding(horizontal = 36.dp, vertical = 12.dp), fontSize = 15.sp, color = AppC.textPrimary)
        }
    }
    val confirm: @Composable () -> Unit = {
        Surface(shape = RoundedCornerShape(22.dp), color = PickerBlue, onClick = onConfirm) {
            Text("确定", modifier = Modifier.padding(horizontal = 40.dp, vertical = 12.dp), fontSize = 15.sp, color = pickerText(), fontWeight = FontWeight.Bold)
        }
    }
    return dismiss to confirm
}

/**
 * 日历选日期弹窗；initial 解析失败时定位到 [focus]（如"放假结束"跟着"放假开始"的月份），
 * [focus] 也为空时回到今天。注意 focus 只影响打开时的月份，不预选日期。
 */
@Composable
fun AppDatePickerDialog(title: String, initial: String, focus: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val init = parseHolidayDate(initial) ?: parseHolidayDate(focus) ?: LocalDate.now()
    var year by remember { mutableIntStateOf(init.year) }
    var month by remember { mutableIntStateOf(init.monthValue) }
    var selected by remember { mutableStateOf(parseHolidayDate(initial)) }
    val first = LocalDate.of(year, month, 1)
    val daysInMonth = first.lengthOfMonth()
    // 周一为第一列：Monday.value=1→0空格、Tuesday=2→1空格…Sunday=7→6空格。
    // （曾用 value%7 会把周一顶出 1 格，整个月错位一天）
    val leadingBlanks = first.dayOfWeek.value - 1
    val gridRows = (leadingBlanks + daysInMonth + 6) / 7
    val (dismissBtn, confirmBtn) = PickerButtons(onDismiss = onDismiss, onConfirm = { selected?.let { onConfirm(it.toString()) } ?: onDismiss() })
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppC.card,
        shape = RoundedCornerShape(20.dp),
        title = { Text(title, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 19.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", modifier = Modifier.clickable {
                        if (month == 1) { month = 12; year-- } else month--
                    }.padding(horizontal = 18.dp, vertical = 6.dp), fontSize = 24.sp, color = AppC.textBody)
                    Spacer(Modifier.weight(1f))
                    Text("%d/%02d".format(year, month), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AppC.textPrimary)
                    Spacer(Modifier.weight(1f))
                    Text("›", modifier = Modifier.clickable {
                        if (month == 12) { month = 1; year++ } else month++
                    }.padding(horizontal = 18.dp, vertical = 6.dp), fontSize = 24.sp, color = AppC.textBody)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    for (d in listOf("一", "二", "三", "四", "五", "六", "日"))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(d, fontSize = 13.sp, color = AppC.textFaint) }
                }
                Spacer(Modifier.height(4.dp))
                for (r in 0 until gridRows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (c in 0..6) {
                            val dayNum = r * 7 + c - leadingBlanks + 1
                            Box(Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                                if (dayNum in 1..daysInMonth) {
                                    val date = LocalDate.of(year, month, dayNum)
                                    val sel = selected == date
                                    Surface(
                                        onClick = { selected = date },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (sel) PickerBlue else Color.Transparent,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    ) {
                                        Text(
                                            "$dayNum",
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            softWrap = false,
                                            fontSize = 16.sp,
                                            color = if (sel) pickerText() else AppC.textPrimary,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = confirmBtn,
        dismissButton = dismissBtn,
    )
}

/** 时分双列滚轮弹窗；initial "HH:mm" 解析失败时默认 08:30。 */
@Composable
fun AppTimePickerDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parts = normalizeTimeText(initial).split(":")
    var hSel by remember { mutableIntStateOf(parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8) }
    var mSel by remember { mutableIntStateOf(parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30) }
    val hours = remember { (0..23).map { "%02d".format(it) } }
    val minutes = remember { (0..59).map { "%02d".format(it) } }
    val (dismissBtn, confirmBtn) = PickerButtons(onDismiss = onDismiss, onConfirm = { onConfirm("%02d:%02d".format(hSel, mSel)) })
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppC.card,
        shape = RoundedCornerShape(20.dp),
        title = { Text(title, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 19.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    Text("时", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 14.sp, color = AppC.textFaint)
                    Text("分", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 14.sp, color = AppC.textFaint)
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    WheelColumn(hours, hSel, { hSel = it }, modifier = Modifier.weight(1f), loop = true)
                    WheelColumn(minutes, mSel, { mSel = it }, modifier = Modifier.weight(1f), loop = true)
                }
            }
        },
        confirmButton = confirmBtn,
        dismissButton = dismissBtn,
    )
}

// ====== 只读输入框外观的触发器（点开对应弹窗） ======
// readOnly 的 TextField 仍会吃掉点击去抢焦点，所以在上面盖一层透明可点击 Box 接管。

@Composable
fun DateInput(value: String, placeholder: String = "", modifier: Modifier = Modifier, focus: String = "", onValueChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        // 输入框负责撑高；matchParentSize 的透明层只接管点击、不参与测量
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            placeholder = { Text(placeholder, fontSize = 13.sp, color = AppC.placeholder) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) AppDatePickerDialog("选择日期", value, focus, onDismiss = { open = false }, onConfirm = { onValueChange(it); open = false })
}

@Composable
fun TimeInput(value: String, placeholder: String = "", modifier: Modifier = Modifier, onValueChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            placeholder = { Text(placeholder, fontSize = 13.sp, color = AppC.placeholder) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) AppTimePickerDialog("选择时间", value, onDismiss = { open = false }, onConfirm = { onValueChange(it); open = false })
}

/** 与 FormField 同布局的日期/时间版（label 在上，点击弹选择器）；focus 为值空时日历定位参考日 */
@Composable
fun DateField(label: String, value: String, placeholder: String = "", focus: String = "", onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = AppC.textMuted)
        DateInput(value, placeholder, Modifier.fillMaxWidth(), focus, onValueChange)
    }
}

@Composable
fun TimeField(label: String, value: String, placeholder: String = "", onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = AppC.textMuted)
        TimeInput(value, placeholder, Modifier.fillMaxWidth(), onValueChange)
    }
}
