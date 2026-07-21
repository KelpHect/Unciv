package com.unciv.ui.screens.pickerscreens

import com.unciv.GUI
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.surroundWithCircle
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.popups.AskTextPopup
import com.unciv.ui.screens.basescreen.BaseScreen

class UnitRenamePopup(val screen: BaseScreen, val unit: MapUnit, val actionOnClose: ()->Unit) {
    init {
        AskTextPopup(
            screen,
            label = "Choose name for [${unit.baseUnit.name}]",
            icon = ImageGetter.getUnitIcon(unit.baseUnit).surroundWithCircle(80f),
            defaultText = unit.instanceName ?: unit.baseUnit.name.tr(hideIcons = true),
            validate = { it != unit.name },
            actionOnOk = { userInput ->
                //If the user inputs an empty string, clear the unit instanceName so the base name is used
                val instanceName = if (userInput == "") null else userInput
                if (!GUI.getMap().renameUnit(unit, instanceName)) actionOnClose()
            }
        ).open()
    }

}
