package com.example.agenthost.automation

sealed class CapabilityValidationResult {
    data object Valid : CapabilityValidationResult()

    data class Invalid(
        val code: String,
        val message: String
    ) : CapabilityValidationResult()
}

class CapabilityValidator {
    fun validate(action: GuiAction, capabilityProfile: CapabilityProfile): CapabilityValidationResult {
        return when (action) {
            is GuiAction.Click -> requireCapability(capabilityProfile.clickSupported, action.actionType())
            is GuiAction.Swipe -> requireCapability(capabilityProfile.swipeSupported, action.actionType())
            is GuiAction.LongPress -> requireCapability(capabilityProfile.longPressSupported, action.actionType())
            is GuiAction.TypeText -> validateText(action.text, capabilityProfile)
            GuiAction.Back -> requireCapability(capabilityProfile.backSupported, action.actionType())
            GuiAction.Home -> requireCapability(capabilityProfile.homeSupported, action.actionType())
            is GuiAction.Wait -> requireCapability(capabilityProfile.waitSupported, action.actionType())
            is GuiAction.AskUser -> requireCapability(capabilityProfile.askUserSupported, action.actionType())
            is GuiAction.Done -> requireCapability(capabilityProfile.doneSupported, action.actionType())
        }
    }

    private fun requireCapability(supported: Boolean, actionType: String): CapabilityValidationResult {
        return if (supported) {
            CapabilityValidationResult.Valid
        } else {
            CapabilityValidationResult.Invalid(
                code = "MODEL_OUTPUT_UNSUPPORTED_ACTION",
                message = "action=$actionType is not supported by current capability profile"
            )
        }
    }

    private fun validateText(
        text: String,
        capabilityProfile: CapabilityProfile
    ): CapabilityValidationResult {
        if (!capabilityProfile.textSupportLevel.supports(text)) {
            return CapabilityValidationResult.Invalid(
                code = "MODEL_OUTPUT_UNSUPPORTED_ACTION",
                message = "action=TYPE_TEXT exceeds textSupportLevel=${capabilityProfile.textSupportLevel.name}"
            )
        }
        return requireCapability(
            supported = capabilityProfile.textSupportLevel != TextSupportLevel.NONE,
            actionType = "TYPE_TEXT"
        )
    }
}
