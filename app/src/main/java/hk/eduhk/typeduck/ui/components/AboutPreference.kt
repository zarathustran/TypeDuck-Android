package hk.eduhk.typeduck.ui.components

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.method.TransformationMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import hk.eduhk.typeduck.R
import hk.eduhk.typeduck.util.Const

class AboutPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
	override fun onBindViewHolder(holder: PreferenceViewHolder) {
		super.onBindViewHolder(holder)
		holder.itemView.findViewById<TextView>(R.id.description_text).apply {
			transformationMethod = EmailLinkTransformationMethod()
			movementMethod = LinkMovementMethod.getInstance()
		}
	}

	internal class EmailLinkTransformationMethod : TransformationMethod {
		private val subject = "TypeDuck Mandarin Issue Report | 國語拼音輸入法問題回報"
		private val body = """
			|
			|
            |App Version: ${Const.displayVersionName}
            |Device Model: ${Build.MODEL} (${Build.MANUFACTURER})
            |Android Version: ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})
			|
			|
		""".trimMargin()

		override fun getTransformation(source: CharSequence, view: View) =
			(source as Spannable).apply {
				Linkify.addLinks(this, Linkify.EMAIL_ADDRESSES)
				for (span in getSpans(0, length, URLSpan::class.java)) {
					span.url.let {
						if (it.startsWith("mailto:")) {
							setSpan(
								URLSpan("${it}?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"),
								getSpanStart(span), getSpanEnd(span), getSpanFlags(span)
							)
							removeSpan(span)
						}
					}
				}
			}

		override fun onFocusChanged(view: View, sourceText: CharSequence, focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {}
	}
}
