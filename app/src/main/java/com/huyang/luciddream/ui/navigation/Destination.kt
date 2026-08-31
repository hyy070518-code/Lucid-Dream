package com.huyang.luciddream.ui.navigation

enum class Destination(
    val route: String,
    val label: String,
    val glyph: String,
) {
    Home("home", "Agent", "◐"),
    Inspector("inspector", "Inspector", "⌁"),
    History("history", "历史", "◷"),
    Settings("settings", "设置", "⚙"),
}
