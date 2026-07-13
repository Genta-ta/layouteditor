package com.itsvks.layouteditor.activities

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsvks.layouteditor.BaseActivity
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.databinding.ActivityEditXMLBinding
import com.itsvks.layouteditor.utils.SBUtils.Companion.make
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class EditXMLActivity : BaseActivity() {
  private var binding: ActivityEditXMLBinding? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityEditXMLBinding.inflate(layoutInflater)

    setContentView(binding!!.getRoot())
    setSupportActionBar(binding!!.topAppBar)
    supportActionBar!!.setTitle(R.string.edit_xml)

    binding!!.topAppBar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

    binding!!.editor.apply {
      setText(intent.getStringExtra(EXTRA_KEY_XML))
      typefaceText = jetBrainsMono()
      typefaceLineNumber = jetBrainsMono()
      isEditable = true
    }
    try {
      loadDefaultThemes()
      ThemeRegistry.getInstance().setTheme("darcula")
      loadDefaultLanguages()

      ensureTextmateTheme()

      val editor = binding!!.editor
      val language = TextMateLanguage.create("text.xml", true)
      editor.setEditorLanguage(language)
    } catch (e: Exception) {
      e.printStackTrace()
    }

    binding!!.fab.setOnClickListener {
      val xmlContent = binding!!.editor.text.toString()

      if (xmlContent.isBlank()) {
        val resultIntent = Intent()
        resultIntent.putExtra(EXTRA_KEY_XML, "")
        setResult(RESULT_OK, resultIntent)
        finish()
        return@setOnClickListener
      }

      if (isValidXml(xmlContent)) {
        val resultIntent = Intent()
        resultIntent.putExtra(EXTRA_KEY_XML, xmlContent)
        setResult(RESULT_OK, resultIntent)
        finish()
      } else {
        MaterialAlertDialogBuilder(this)
          .setTitle(R.string.xml_invalid)
          .setMessage(R.string.msg_xml_invalid)
          .setPositiveButton(R.string.okay) { d, _ -> d.dismiss() }
          .show()
      }
    }

    binding!!.editor.setOnScrollChangeListener { _, _, y, _, oldY ->
      if (y > oldY + 20 && binding!!.fab.isExtended) {
        binding!!.fab.shrink()
      }
      if (y < oldY - 20 && !binding!!.fab.isExtended) {
        binding!!.fab.extend()
      }
      if (y == 0) {
        binding!!.fab.extend()
      }
    }
  }

  private fun isValidXml(xml: String): Boolean {
    return try {
      val factory = DocumentBuilderFactory.newInstance()
      val builder = factory.newDocumentBuilder()
      val source = InputSource(StringReader(xml))
      builder.parse(source)
      true
    } catch (e: Exception) {
      false
    }
  }

  override fun onDestroy() {
    binding = null
    super.onDestroy()
  }

  @Throws(Exception::class)
  private fun loadDefaultThemes() {
    FileProviderRegistry.getInstance()
      .addFileProvider(AssetsFileResolver(applicationContext.assets))

    val themeRegistry = ThemeRegistry.getInstance()
    val path = "editor/textmate/darcula.json"
    themeRegistry.loadTheme(
      ThemeModel(
        IThemeSource.fromInputStream(
          FileProviderRegistry.getInstance().tryGetInputStream(path), path, null
        ), "darcula"
      )
    )

    themeRegistry.setTheme("darcula")
  }

  private fun loadDefaultLanguages() {
    GrammarRegistry.getInstance().loadGrammars("editor/textmate/languages.json")
  }

  @Throws(Exception::class)
  private fun ensureTextmateTheme() {
    val editor = binding!!.editor
    var editorColorScheme: EditorColorScheme? = editor.colorScheme
    if (editorColorScheme !is TextMateColorScheme) {
      editorColorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
      editor.colorScheme = editorColorScheme
    }
  }

  private fun jetBrainsMono(): Typeface? {
    return ResourcesCompat.getFont(this, R.font.jetbrains_mono_regular)
  }

  companion object {
    const val EXTRA_KEY_XML: String = "xml"
  }
}
