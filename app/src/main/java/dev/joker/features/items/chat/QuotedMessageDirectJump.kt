package dev.joker.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.joker.reflekt.reflekt
import dev.joker.R
import dev.joker.dexkit.abc.IResolveDex
import dev.joker.dexkit.dsl.data
import dev.joker.dexkit.dsl.dexClass
import dev.joker.dexkit.dsl.dexMethod
import dev.joker.features.api.core.WeMessageApi
import dev.joker.features.core.ClickableFeature
import dev.joker.features.core.FeatureCategoryIds
import dev.joker.preferences.WePrefs.Companion.prefOption
import dev.joker.ui.content.AlertDialogContent
import dev.joker.ui.content.TextButton
import dev.joker.ui.content.m3.SegmentedColumn
import dev.joker.ui.content.m3.SwitchWidget
import dev.joker.ui.utils.showComposeDialog
import dev.joker.utils.enumValueOfClass

object QuotedMessageDirectJump : ClickableFeature(), IResolveDex {

    override val technicalId = "引用消息直达"
    override val nameRes = R.string.feature_quoted_message_direct_jump_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_quoted_message_direct_jump_description

    private var messageListDirectJump by prefOption("chat_quoted_direct_jump_message_list", true)
    private var inputBoxDirectJump by prefOption("chat_quoted_direct_jump_input_box", true)

    private val methodClickEvent by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            usingEqStrings(
                "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                "handleItemClickEvent,quotedMsg is null!"
            )
        }
    }
    private val methodClickToPositionEvent by dexMethod {
        matcher {
            declaredClass(methodClickEvent.data.declaredClassName)
            usingEqStrings(
                "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                "handleItemClickToPositionEvent,quotedMsg is null!"
            )
        }
    }
    private val methodGetQuoteMessageInfo by dexMethod {
        matcher {
            declaredClass(methodClickEvent.data.declaredClassName)
            usingStrings(
                "MicroMsg.msgquote.QuoteMsgSourceClickLogic",
                "%s msgId:%s msgSvrId:%s"
            )
        }
    }
    private val classEnumQuoteJumpToPositionSource by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings("QuoteLongClickFromQuoteView", "QuoteClickFromTextPreviewLocateView")
        }
    }
    override fun onEnable() {
        methodClickEvent.hookBefore {
            val isInputBox = args[1] == null
            val shouldDirectJump = if (isInputBox) inputBoxDirectJump else messageListDirectJump
            if (!shouldDirectJump) return@hookBefore

            val chattingContext = args[0]
            val view = args[2]
            val longValue = args[3]
            val stringValue = args[4]
            val msgQuoteItem = args[5]
            val chattingItemHolder = args[7]!!
            val chattingItem = chattingItemHolder.reflekt()
                .firstField { type { it != String::class.java } }.get()
            val mGetQuoteMessageInfo = methodGetQuoteMessageInfo.method
            var msgInfo: Any
            if (mGetQuoteMessageInfo.parameterCount == 6) {
                msgInfo = mGetQuoteMessageInfo.invoke(
                    null,
                    false /* isGroupChat: this arg is ignored */,
                    WeMessageApi.methodChattingContextGetTalker.method.invoke(chattingContext),
                    longValue,
                    stringValue,
                    msgQuoteItem,
                    "handleQuoteMsgClick" /* hardcoded in original code */
                )!!
            } else {
                msgInfo = mGetQuoteMessageInfo.invoke(
                    null,
                    false /* isGroupChat: this arg is ignored */,
                    WeMessageApi.methodChattingContextGetTalker.method.invoke(chattingContext),
                    longValue,
                    msgQuoteItem,
                    "handleQuoteMsgClick" /* hardcoded in original code */
                )!!
            }
            val mClickToPositionEvent = methodClickToPositionEvent.method
            if (mClickToPositionEvent.parameterCount == 8) {
                methodClickToPositionEvent.method.invoke(
                    null,
                    chattingContext,
                    chattingItem,
                    msgInfo,
                    view,
                    longValue,
                    stringValue,
                    msgQuoteItem,
                    enumValueOfClass(classEnumQuoteJumpToPositionSource.clazz, "QuoteLongClickFromQuoteView")
                )
            } else {
                methodClickToPositionEvent.method.invoke(
                    null,
                    chattingContext,
                    chattingItem,
                    msgInfo,
                    view,
                    longValue,
                    msgQuoteItem,
                    true
                )
            }
            result = null
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var messageList by remember { mutableStateOf(messageListDirectJump) }
            var inputBox by remember { mutableStateOf(inputBoxDirectJump) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_quoted_message_direct_jump_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.chat_quote_jump_message_list),
                                description = stringResource(R.string.chat_quote_jump_message_list_description),
                                checked = messageList,
                                onCheckedChange = {
                                    messageList = it
                                    messageListDirectJump = it
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.chat_quote_jump_input_box),
                                description = stringResource(R.string.chat_quote_jump_input_box_description),
                                checked = inputBox,
                                onCheckedChange = {
                                    inputBox = it
                                    inputBoxDirectJump = it
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
