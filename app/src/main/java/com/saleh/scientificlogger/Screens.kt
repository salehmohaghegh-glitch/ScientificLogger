package com.saleh.scientificlogger

sealed class Screen(val route: String) {
    object Main : Screen("main_screen")
    object Graphs : Screen("graphs_screen")
    object Settings : Screen("settings_screen")
    object Help : Screen("help_screen")
}
