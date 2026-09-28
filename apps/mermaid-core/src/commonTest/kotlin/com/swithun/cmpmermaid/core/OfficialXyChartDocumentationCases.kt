package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/xyChart.md.
 * Upstream document SHA-256: f129b0dae594a86019c88af5f52279300089d5b3bfee50705910fb4d48528f66
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:xychart-doc-fixtures
 */
internal data class MermaidXyChartDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialXyChartDocumentationCases: List<MermaidXyChartDocCase> = listOf(
    MermaidXyChartDocCase(
        id = "001_example",
        title = "Example",
        source = """
xychart
    title "Sales Revenue"
    x-axis [jan, feb, mar, apr, may, jun, jul, aug, sep, oct, nov, dec]
    y-axis "Revenue (in ${'$'})" 4000 --> 11000
    bar [5000, 6000, 7500, 8200, 9500, 10500, 11000, 10200, 9200, 8500, 7000, 6000]
    line [5000, 6000, 7500, 8200, 9500, 10500, 11000, 10200, 9200, 8500, 7000, 6000]
        """.trimIndent(),
    ),
    MermaidXyChartDocCase(
        id = "002_legend_v11_17_0",
        title = "Legend (v11.17.0+)",
        source = """
xychart-beta
  title "An Example Chart"
  x-axis ["90d", "60d", "30d", "7d", "1d", "Current"]
  y-axis "Seconds" 0 --> 198.2
  line "avg" [48.1, 41.5, 45.7, 72.8, 67.7, 59.9]
  line "p50" [38.2, 36.8, 39.7, 54.5, 49.0, 38.4]
  line "p95" [112.2, 75.3, 103.0, 177.0, 180.2, 109.4]
        """.trimIndent(),
    ),
    MermaidXyChartDocCase(
        id = "003_setting_colors_for_lines_and_bars",
        title = "Setting Colors for Lines and Bars",
        source = """
---
config:
  themeVariables:
    xyChart:
      plotColorPalette: '#000000, #0000FF, #00FF00, #FF0000'
---
xychart
title "Different Colors in xyChart"
x-axis "categoriesX" ["Category 1", "Category 2", "Category 3", "Category 4"]
y-axis "valuesY" 0 --> 50
%% Black line
line [10,20,30,40]
%% Blue bar
bar [20,30,25,35]
%% Green bar
bar [15,25,20,30]
%% Red line
line [5,15,25,35]
        """.trimIndent(),
    ),
    MermaidXyChartDocCase(
        id = "004_displaying_individual_values_on_a_bar_chart_v11_14_0",
        title = "Displaying individual values on a bar chart (v11.14.0+)",
        source = """
---
config:
    xyChart:
        showDataLabel: true
---
xychart
    title "Genres in top 100 book survey of 2025"
    x-axis [comedy, romance, mystery, crime, "non fiction", other]
    y-axis "Number of Books" 0 --> 30
    bar [12,2,20,25,17,24]
        """.trimIndent(),
    ),
    MermaidXyChartDocCase(
        id = "005_displaying_individual_values_on_a_bar_chart_v11_14_0",
        title = "Displaying individual values on a bar chart (v11.14.0+)",
        source = """
---
config:
    xyChart:
        showDataLabel: true
        showDataLabelOutsideBar: true
---
xychart
    title "Genres in top 100 book survey of 2025"
    x-axis [comedy, romance, mystery, crime, "non fiction", other]
    y-axis "Number of Books" 0 --> 30
    bar [12,2,20,25,17,24]
        """.trimIndent(),
    ),
    MermaidXyChartDocCase(
        id = "006_per_point_text_labels_for_line_charts_v11_16_0",
        title = "Per-point text labels for line charts (v11.16.0+)",
        source = """
xychart
    title "Smallest AI models scoring above 60% on MMLU"
    x-axis "Date" ["Apr 2022", "Feb 2023", "Jul 2023", "Sep 2023", "Apr 2024"]
    y-axis "Parameters (B)" 0 --> 600
    line [540 "PaLM", 65 "LLaMA-65B", 34 "Llama 2 34B", 7 "Mistral 7B", 3.8 "Phi-3-mini"]
        """.trimIndent(),
    ),
    MermaidXyChartDocCase(
        id = "007_per_point_text_labels_for_line_charts_v11_16_0",
        title = "Per-point text labels for line charts (v11.16.0+)",
        source = """
xychart
    title "Quarterly Performance"
    x-axis [Q1, Q2, Q3, Q4]
    y-axis "Revenue (${'$'}M)" 0 --> 100
    line [25 "Launch", 45, 72, 90 "Target Hit"]
        """.trimIndent(),
    ),
    MermaidXyChartDocCase(
        id = "008_example_on_config_and_theme",
        title = "Example on config and theme",
        source = """
---
config:
    xyChart:
        width: 900
        height: 600
        showDataLabel: true
    themeVariables:
        xyChart:
            titleColor: "#ff0000"
---
xychart
    title "Sales Revenue"
    x-axis [jan, feb, mar, apr, may, jun, jul, aug, sep, oct, nov, dec]
    y-axis "Revenue (in ${'$'})" 4000 --> 11000
    bar [5000, 6000, 7500, 8200, 9500, 10500, 11000, 10200, 9200, 8500, 7000, 6000]
    line [5000, 6000, 7500, 8200, 9500, 10500, 11000, 10200, 9200, 8500, 7000, 6000]
        """.trimIndent(),
    ),
)
