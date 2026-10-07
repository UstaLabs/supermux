package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/timeline.md.
 * Upstream document SHA-256: 787400fc48f737b77af84d9fef8ab713f52d5892542d6eb4357c7cf2ff93fa46
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:timeline-doc-fixtures
 */
internal data class MermaidTimelineDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialTimelineDocumentationCases: List<MermaidTimelineDocCase> = listOf(
    MermaidTimelineDocCase(
        id = "001_an_example_of_a_timeline",
        title = "An example of a timeline",
        source = """
timeline
    title History of Social Media Platform
    2002 : LinkedIn
    2004 : Facebook
         : Google
    2005 : YouTube
    2006 : Twitter
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "002_syntax",
        title = "Syntax",
        source = """
timeline
    title History of Social Media Platform
    2002 : LinkedIn
    2004 : Facebook : Google
    2005 : YouTube
    2006 : Twitter
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "003_grouping_of_time_periods_in_sections_ages",
        title = "Grouping of time periods in sections/ages",
        source = """
timeline
    title Timeline of Industrial Revolution
    section 17th-20th century
        Industry 1.0 : Machinery, Water power, Steam <br>power
        Industry 2.0 : Electricity, Internal combustion engine, Mass production
        Industry 3.0 : Electronics, Computers, Automation
    section 21st century
        Industry 4.0 : Internet, Robotics, Internet of Things
        Industry 5.0 : Artificial intelligence, Big data, 3D printing
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "004_wrapping_of_text_for_long_time_periods_or_events",
        title = "Wrapping of text for long time-periods or events",
        source = """
timeline
        title England's History Timeline
        section Stone Age
          7600 BC : Britain's oldest known house was built in Orkney, Scotland
          6000 BC : Sea levels rise and Britain becomes an island.<br> The people who live here are hunter-gatherers.
        section Bronze Age
          2300 BC : People arrive from Europe and settle in Britain. <br>They bring farming and metalworking.
                  : New styles of pottery and ways of burying the dead appear.
          2200 BC : The last major building works are completed at Stonehenge.<br> People now bury their dead in stone circles.
                  : The first metal objects are made in Britain.Some other nice things happen. it is a good time to be alive.
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "005_wrapping_of_text_for_long_time_periods_or_events",
        title = "Wrapping of text for long time-periods or events",
        source = """
timeline
        title MermaidChart 2023 Timeline
        section 2023 Q1 <br> Release Personal Tier
          Bullet 1 : sub-point 1a : sub-point 1b
               : sub-point 1c
          Bullet 2 : sub-point 2a : sub-point 2b
        section 2023 Q2 <br> Release XYZ Tier
          Bullet 3 : sub-point <br> 3a : sub-point 3b
               : sub-point 3c
          Bullet 4 : sub-point 4a : sub-point 4b
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "006_direction_v11_14_0",
        title = "Direction (v11.14.0+)",
        source = """
timeline TD
  title MermaidChart 2023 Timeline
    section 2023 Q1 <br> Release Personal Tier
      Bullet 1 : sub-point 1a : sub-point 1b
      Bullet 2 : sub-point 2a : sub-point 2b
    section 2023 Q2 <br> Release XYZ Tier
      Bullet 3 : sub-point <br> 3a : sub-point 3b
      Bullet 4 : sub-point 4a : sub-point 4b
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "007_styling_of_time_periods_and_events",
        title = "Styling of time periods and events",
        source = """
timeline
        title History of Social Media Platform
          2002 : LinkedIn
          2004 : Facebook : Google
          2005 : YouTube
          2006 : Twitter
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "008_styling_of_time_periods_and_events",
        title = "Styling of time periods and events",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'base'
  timeline:
    disableMulticolor: true
---
    timeline
        title History of Social Media Platform
          2002 : LinkedIn
          2004 : Facebook : Google
          2005 : YouTube
          2006 : Twitter
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "009_customizing_color_scheme",
        title = "Customizing Color scheme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
  themeVariables:
    cScale0: '#ff0000'
    cScaleLabel0: '#ffffff'
    cScale1: '#00ff00'
    cScale2: '#0000ff'
    cScaleLabel2: '#ffffff'
---
       timeline
        title History of Social Media Platform
          2002 : LinkedIn
          2004 : Facebook : Google
          2005 : YouTube
          2006 : Twitter
          2007 : Tumblr
          2008 : Instagram
          2010 : Pinterest
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "010_base_theme",
        title = "Base Theme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'base'
---
    timeline
        title History of Social Media Platform
          2002 : LinkedIn
          2004 : Facebook : Google
          2005 : YouTube
          2006 : Twitter
          2007 : Tumblr
          2008 : Instagram
          2010 : Pinterest
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "011_forest_theme",
        title = "Forest Theme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'forest'
---
    timeline
        title History of Social Media Platform
          2002 : LinkedIn
          2004 : Facebook : Google
          2005 : YouTube
          2006 : Twitter
          2007 : Tumblr
          2008 : Instagram
          2010 : Pinterest
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "012_dark_theme",
        title = "Dark Theme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'dark'
---
    timeline
        title History of Social Media Platform
          2002 : LinkedIn
          2004 : Facebook : Google
          2005 : YouTube
          2006 : Twitter
          2007 : Tumblr
          2008 : Instagram
          2010 : Pinterest
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "013_the_default_theme",
        title = "The `default` Theme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'default'
---
    timeline
        title History of Social Media Platform
          2002 : LinkedIn
          2004 : Facebook : Google
          2005 : YouTube
          2006 : Twitter
          2007 : Tumblr
          2008 : Instagram
          2010 : Pinterest
        """.trimIndent(),
    ),
    MermaidTimelineDocCase(
        id = "014_neutral_theme",
        title = "Neutral Theme",
        source = """
---
config:
  logLevel: 'debug'
  theme: 'neutral'
---
    timeline
        title History of Social Media Platform
          2002 : LinkedIn
          2004 : Facebook : Google
          2005 : YouTube
          2006 : Twitter
          2007 : Tumblr
          2008 : Instagram
          2010 : Pinterest
        """.trimIndent(),
    ),
)
