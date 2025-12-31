package com.saleh.scientificlogger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.patrykandpatryk.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatryk.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatryk.vico.compose.chart.Chart
import com.patrykandpatryk.vico.compose.chart.line.lineChart
import com.patrykandpatryk.vico.compose.component.shapeComponent
import com.patrykandpatryk.vico.compose.component.textComponent
import com.patrykandpatryk.vico.compose.legend.horizontalLegend
import com.patrykandpatryk.vico.core.chart.line.LineChart
import com.patrykandpatryk.vico.core.component.shape.Shapes
import com.patrykandpatryk.vico.core.entry.ChartEntryModelProducer

@Composable
fun ChartDialog(
    title: String,
    chartModelProducer: ChartEntryModelProducer,
    onDismissRequest: () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White)
                .padding(16.dp)
        ) {
            Text(text = title)
            Chart(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp),
                chart = lineChart(
                    lines = listOf(
                        LineChart.LineSpec(lineColor = Color.Red.hashCode()),
                        LineChart.LineSpec(lineColor = Color.Green.hashCode()),
                        LineChart.LineSpec(lineColor = Color.Blue.hashCode()),
                        LineChart.LineSpec(lineColor = Color.Black.hashCode(), lineThicknessDp = 4f),
                    )
                ),
                chartModelProducer = chartModelProducer,
                startAxis = rememberStartAxis(),
                bottomAxis = rememberBottomAxis(),
                legend = horizontalLegend(
                    items = listOf(
                        com.patrykandpatryk.vico.core.legend.LegendItem(shapeComponent(Shapes.pillShape, Color.Red), textComponent().apply { this.color = Color.Red.hashCode() }, "X"),
                        com.patrykandpatryk.vico.core.legend.LegendItem(shapeComponent(Shapes.pillShape, Color.Green), textComponent().apply { this.color = Color.Green.hashCode() }, "Y"),
                        com.patrykandpatryk.vico.core.legend.LegendItem(shapeComponent(Shapes.pillShape, Color.Blue), textComponent().apply { this.color = Color.Blue.hashCode() }, "Z"),
                        com.patrykandpatryk.vico.core.legend.LegendItem(shapeComponent(Shapes.pillShape, Color.Black), textComponent().apply { this.color = Color.Black.hashCode() }, "Total"),
                    ),
                    iconSize = 8.dp,
                    iconPadding = 8.dp,
                )
            )
        }
    }
}
