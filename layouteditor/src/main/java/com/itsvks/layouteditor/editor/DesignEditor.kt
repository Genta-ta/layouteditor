package com.itsvks.layouteditor.editor

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.DragEvent
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.View
import android.view.View.OnDragListener
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.VibrateUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsvks.layouteditor.BuildConfig
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.adapters.AppliedAttributesAdapter
import com.itsvks.layouteditor.databinding.ShowAttributesDialogBinding
import com.itsvks.layouteditor.editor.dialogs.AttributeDialog
import com.itsvks.layouteditor.editor.dialogs.BooleanDialog
import com.itsvks.layouteditor.editor.dialogs.ColorDialog
import com.itsvks.layouteditor.editor.dialogs.DimensionDialog
import com.itsvks.layouteditor.editor.dialogs.EnumDialog
import com.itsvks.layouteditor.editor.dialogs.FlagDialog
import com.itsvks.layouteditor.editor.dialogs.IdDialog
import com.itsvks.layouteditor.editor.dialogs.NumberDialog
import com.itsvks.layouteditor.editor.dialogs.SizeDialog
import com.itsvks.layouteditor.editor.dialogs.StringDialog
import com.itsvks.layouteditor.editor.dialogs.ViewDialog
import com.itsvks.layouteditor.editor.initializer.AttributeInitializer
import com.itsvks.layouteditor.editor.initializer.AttributeMap
import com.itsvks.layouteditor.managers.IdManager
import com.itsvks.layouteditor.managers.IdManager.addId
import com.itsvks.layouteditor.managers.IdManager.getViewId
import com.itsvks.layouteditor.managers.IdManager.removeId
import com.itsvks.layouteditor.managers.PreferencesManager.isEnableVibration
import com.itsvks.layouteditor.managers.PreferencesManager.isShowStroke
import com.itsvks.layouteditor.managers.UndoRedoManager
import com.itsvks.layouteditor.tools.XmlLayoutGenerator
import com.itsvks.layouteditor.tools.XmlLayoutParser
import com.itsvks.layouteditor.utils.ArgumentUtil.parseType
import com.itsvks.layouteditor.utils.Constants
import com.itsvks.layouteditor.utils.EditorLog
import com.itsvks.layouteditor.utils.FileUtil
import com.itsvks.layouteditor.utils.InvokeUtil
import com.itsvks.layouteditor.utils.Utils
import com.itsvks.layouteditor.views.StructureView
import kotlin.math.abs

class DesignEditor : LinearLayout {
  companion object {
    private const val TAG = "DesignEditor"
  }

  var viewType: ViewType? = null
    set(value) {
      field = value
      isBlueprint = value == ViewType.BLUEPRINT
      setBlueprintOnChildren()
      invalidate()
    }
  var deviceConfiguration: DeviceConfiguration? = null
  var apiLevel: APILevel? = null

  lateinit var viewAttributeMap: HashMap<View, AttributeMap>
    private set

  private lateinit var paint: Paint
  private lateinit var shadow: View

  private lateinit var attributes: HashMap<String, List<HashMap<String, Any>>>
  private lateinit var parentAttributes: HashMap<String, List<HashMap<String, Any>>>
  private lateinit var initializer: AttributeInitializer

  private var isBlueprint = false
  private var structureView: StructureView? = null
  private var undoRedoManager: UndoRedoManager? = null

  private var selectedView: View? = null
  private var activeHandle: Int = -1
  private var resizeFirstTouchX: Float = 0f
  private var resizeFirstTouchY: Float = 0f
  private lateinit var handlePaint: Paint
  private lateinit var selectionBorderPaint: Paint
  private var handleRadius: Int = 0
  private var handleTouchSlop: Int = 0
  private var resizeBaseLeft: Int = 0
  private var resizeBaseTop: Int = 0
  private var resizeBaseWidth: Int = 0
  private var resizeBaseHeight: Int = 0
  private val savedLayoutTransitions = HashMap<ViewGroup, LayoutTransition?>()

  constructor(context: Context) : super(context) {
    init(context)
  }

  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
    init(context)
  }

  constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
    context,
    attrs,
    defStyleAttr
  ) {
    init(context)
  }

  private fun init(context: Context) {
    viewType = ViewType.DESIGN
    isBlueprint = false
    deviceConfiguration = DeviceConfiguration(DeviceSize.LARGE)
    initAttributes()
    shadow = View(context)
    paint = Paint()

    shadow.setBackgroundColor(
      MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
    )
    shadow.layoutParams = ViewGroup.LayoutParams(
      Utils.pxToDp(context, 50),
      Utils.pxToDp(context, 35)
    )
    paint.strokeWidth = Utils.pxToDp(context, 3).toFloat()

    handleRadius = Utils.pxToDp(context, 10)
    handleTouchSlop = Utils.pxToDp(context, 30)

    handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      style = Paint.Style.FILL
    }

    selectionBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Constants.DESIGN_DASH_COLOR
      style = Paint.Style.STROKE
      strokeWidth = Utils.pxToDp(context, 2).toFloat()
    }

    orientation = VERTICAL
    setTransition(this)
    setDragListener(this)

    toggleStrokeWidgets()
    setBlueprintOnChildren()
  }

  override fun dispatchDraw(canvas: Canvas) {
    super.dispatchDraw(canvas)
    drawSelectionIndicator(canvas)
    when (viewType) {
      ViewType.BLUEPRINT -> drawBlueprint(canvas)
      ViewType.DESIGN -> drawDesign(canvas)
      else -> drawDesign(canvas)
    }
    when (deviceConfiguration!!.size) {
      DeviceSize.SMALL -> {
        scaleX = 0.75f
        scaleY = 0.75f
      }

      DeviceSize.MEDIUM -> {
        scaleX = 0.85f
        scaleY = 0.85f
      }

      DeviceSize.LARGE -> {
        scaleX = 0.95f
        scaleY = 0.95f
      }
    }
  }

  private fun drawBlueprint(canvas: Canvas) {
    paint.color = Constants.BLUEPRINT_DASH_COLOR
    setBackgroundColor(Constants.BLUEPRINT_BACKGROUND_COLOR)
    Utils.drawDashPathStroke(this, canvas, paint)
  }

  private fun drawDesign(canvas: Canvas) {
    paint.color = Constants.DESIGN_DASH_COLOR
    setBackgroundColor(Utils.getSurfaceColor(context))
    Utils.drawDashPathStroke(this, canvas, paint)
  }

  private fun getSelectedViewBounds(): RectF? {
    val sv = selectedView ?: return null
    var left = sv.left.toFloat()
    var top = sv.top.toFloat()
    var p = sv.parent as? View
    while (p != null && p !== this) {
      left += p.left.toFloat()
      top += p.top.toFloat()
      p = p.parent as? View
    }
    return RectF(left, top, left + sv.width.toFloat(), top + sv.height.toFloat())
  }

  private fun getHandlePositions(bounds: RectF): Array<PointF> {
    val mx = bounds.centerX()
    val my = bounds.centerY()
    return arrayOf(
      PointF(bounds.left, bounds.top),
      PointF(mx, bounds.top),
      PointF(bounds.right, bounds.top),
      PointF(bounds.left, my),
      PointF(bounds.right, my),
      PointF(bounds.left, bounds.bottom),
      PointF(mx, bounds.bottom),
      PointF(bounds.right, bounds.bottom)
    )
  }

  private fun drawSelectionIndicator(canvas: Canvas) {
    val sv = selectedView ?: return
    if (sv.parent == null) return
    val bounds = getSelectedViewBounds() ?: return
    if (BuildConfig.DEBUG) EditorLog.d(TAG, "drawSelectionIndicator: bounds=$bounds, sv.left=${sv.left}, sv.top=${sv.top}, sv.w=${sv.width}, sv.h=${sv.height}")

    selectionBorderPaint.color = if (isBlueprint) Constants.BLUEPRINT_DASH_COLOR else Constants.DESIGN_DASH_COLOR
    selectionBorderPaint.style = Paint.Style.STROKE
    canvas.drawRect(bounds, selectionBorderPaint)

    val positions = getHandlePositions(bounds)
    for (pos in positions) {
      handlePaint.color = Color.WHITE
      canvas.drawCircle(pos.x, pos.y, handleRadius.toFloat(), handlePaint)
      selectionBorderPaint.style = Paint.Style.STROKE
      canvas.drawCircle(pos.x, pos.y, handleRadius.toFloat(), selectionBorderPaint)
    }
  }

  override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
    if (BuildConfig.DEBUG) EditorLog.d(TAG, "onInterceptTouchEvent: action=${ev.action}, x=${ev.x}, y=${ev.y}, selectedView=$selectedView")
    if (ev.action == MotionEvent.ACTION_DOWN && selectedView != null) {
      val bounds = getSelectedViewBounds()
      if (BuildConfig.DEBUG) EditorLog.d(TAG, "  bounds=$bounds, handleTouchSlop=$handleTouchSlop")
      if (bounds != null) {
        val positions = getHandlePositions(bounds)
        for (i in positions.indices) {
          val dx = ev.x - positions[i].x
          val dy = ev.y - positions[i].y
          val dist2 = dx * dx + dy * dy
          if (BuildConfig.DEBUG) EditorLog.d(TAG, "  handle[$i]: pos=(${positions[i].x},${positions[i].y}), dist=$dist2, threshold=${handleTouchSlop * handleTouchSlop}")
          if (dist2 < handleTouchSlop * handleTouchSlop) {
            if (BuildConfig.DEBUG) EditorLog.d(TAG, "  >>> HANDLE HIT: $i <<<")
            activeHandle = i
            resizeFirstTouchX = ev.x
            resizeFirstTouchY = ev.y
            val sv = selectedView!!
            resizeBaseLeft = sv.left
            resizeBaseTop = sv.top
            resizeBaseWidth = sv.width
            resizeBaseHeight = sv.height
            setParentsLayoutSuppressed(selectedView, true)
            return true
          }
        }
      }
    }
    return super.onInterceptTouchEvent(ev)
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (BuildConfig.DEBUG) EditorLog.d(TAG, "onTouchEvent: action=${event.action}, activeHandle=$activeHandle, selectedView=$selectedView")
    when (event.action) {
      MotionEvent.ACTION_DOWN -> {
        if (activeHandle != -1) {
          if (BuildConfig.DEBUG) EditorLog.d(TAG, "  already intercepting handle=$activeHandle, skip")
          return true
        }
        if (selectedView != null) {
          val bounds = getSelectedViewBounds()
          if (bounds != null) {
            val positions = getHandlePositions(bounds)
            for (i in positions.indices) {
              val dx = event.x - positions[i].x
              val dy = event.y - positions[i].y
              if (dx * dx + dy * dy < handleTouchSlop * handleTouchSlop) {
                if (BuildConfig.DEBUG) EditorLog.d(TAG, "  >>> TOUCH DOWN HANDLE: $i <<<")
                activeHandle = i
                resizeFirstTouchX = event.x
                resizeFirstTouchY = event.y
                val sv = selectedView!!
                resizeBaseLeft = sv.left
                resizeBaseTop = sv.top
                resizeBaseWidth = sv.width
                resizeBaseHeight = sv.height
                setParentsLayoutSuppressed(selectedView, true)
                return true
              }
            }
          }
        }
        if (BuildConfig.DEBUG) EditorLog.d(TAG, "  no handle hit, deselecting")
        selectedView = null
        invalidate()
      }

      MotionEvent.ACTION_MOVE -> {
        if (activeHandle != -1 && selectedView != null) {
          val dx = event.x - resizeFirstTouchX
          val dy = event.y - resizeFirstTouchY
          if (BuildConfig.DEBUG) EditorLog.d(TAG, "  RESIZE: dx=$dx, dy=$dy, activeHandle=$activeHandle")
          resizeSelectedView(dx, dy)
          return true
        }
      }

      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
        if (activeHandle != -1 && selectedView != null) {
          if (BuildConfig.DEBUG) EditorLog.d(TAG, "  RESIZE END: releasing handle=$activeHandle")
          val sv = selectedView!!
          setParentsLayoutSuppressed(selectedView, false)
          val lp = sv.layoutParams
          if (lp != null) {
            lp.width = sv.width
            lp.height = sv.height
            sv.layoutParams = lp
          }
          activeHandle = -1
          updateUndoRedoHistory()
          updateStructure()
          return true
        }
      }
    }
    return true
  }

  private fun resizeSelectedView(dx: Float, dy: Float) {
    val sv = selectedView ?: return
    val minSize = Utils.pxToDp(context, 20)

    var newLeft = sv.left
    var newTop = sv.top
    var newWidth = resizeBaseWidth
    var newHeight = resizeBaseHeight

    when (activeHandle) {
      0 -> {
        newWidth = Math.max(minSize, resizeBaseWidth - dx.toInt())
        newHeight = Math.max(minSize, resizeBaseHeight - dy.toInt())
        newLeft = resizeBaseLeft + resizeBaseWidth - newWidth
        newTop = resizeBaseTop + resizeBaseHeight - newHeight
      }
      1 -> {
        newHeight = Math.max(minSize, resizeBaseHeight - dy.toInt())
        newTop = resizeBaseTop + resizeBaseHeight - newHeight
      }
      2 -> {
        newWidth = Math.max(minSize, resizeBaseWidth + dx.toInt())
        newHeight = Math.max(minSize, resizeBaseHeight - dy.toInt())
        newTop = resizeBaseTop + resizeBaseHeight - newHeight
      }
      3 -> {
        newWidth = Math.max(minSize, resizeBaseWidth - dx.toInt())
        newLeft = resizeBaseLeft + resizeBaseWidth - newWidth
      }
      4 -> {
        newWidth = Math.max(minSize, resizeBaseWidth + dx.toInt())
      }
      5 -> {
        newWidth = Math.max(minSize, resizeBaseWidth - dx.toInt())
        newHeight = Math.max(minSize, resizeBaseHeight + dy.toInt())
        newLeft = resizeBaseLeft + resizeBaseWidth - newWidth
      }
      6 -> {
        newHeight = Math.max(minSize, resizeBaseHeight + dy.toInt())
      }
      7 -> {
        newWidth = Math.max(minSize, resizeBaseWidth + dx.toInt())
        newHeight = Math.max(minSize, resizeBaseHeight + dy.toInt())
      }
    }
    sv.layout(newLeft, newTop, newLeft + newWidth, newTop + newHeight)
    if (BuildConfig.DEBUG) EditorLog.d(TAG, "  resize: left=$newLeft, top=$newTop, w=$newWidth, h=$newHeight")
    invalidate()
  }

  private fun setParentsLayoutSuppressed(view: View?, suppressed: Boolean) {
    if (BuildConfig.DEBUG) EditorLog.d(TAG, "setParentsLayoutSuppressed: suppressed=$suppressed, view=$view")
    var current: View? = view?.parent as? View
    while (current != null) {
      if (current is ViewGroup) {
        if (suppressed) {
          savedLayoutTransitions[current] = current.layoutTransition
          current.layoutTransition = null
          current.suppressLayout(true)
          current.requestDisallowInterceptTouchEvent(true)
        } else {
          current.suppressLayout(false)
          current.layoutTransition = savedLayoutTransitions.remove(current)
          current.requestDisallowInterceptTouchEvent(false)
        }
      }
      current = current.parent as? View
    }
  }

  private fun selectView(view: View?) {
    if (BuildConfig.DEBUG) EditorLog.d(TAG, "selectView: ${view?.javaClass?.simpleName}, activeHandle=$activeHandle")
    selectedView = view
    invalidate()
  }

  fun previewLayout(deviceConfiguration: DeviceConfiguration?, apiLevel: APILevel?) {
    this.deviceConfiguration = deviceConfiguration
    this.apiLevel = apiLevel
  }

  fun resizeLayout(deviceConfiguration: DeviceConfiguration?) {
    this.deviceConfiguration = deviceConfiguration
    invalidate()
  }

  private fun setTransition(group: ViewGroup) {
    if (group is RecyclerView) return
    LayoutTransition().apply {
      disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING)
      enableTransitionType(LayoutTransition.CHANGING)
      setDuration(150)
    }.also { group.layoutTransition = it }
  }

  private fun toggleStrokeWidgets() {
    try {
      for (view in viewAttributeMap.keys) {
        val cls = view.javaClass
        val method = cls.getMethod("setStrokeEnabled", Boolean::class.javaPrimitiveType)
        method.invoke(view, isShowStroke)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  private fun setBlueprintOnChildren() {
    try {
      for (view in viewAttributeMap.keys) {
        val cls = view.javaClass
        val method = cls.getMethod("setBlueprint", Boolean::class.javaPrimitiveType)
        method.invoke(view, isBlueprint)
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }

  private fun setDragListener(group: ViewGroup) {
    group.setOnDragListener(
      OnDragListener { host, event ->
        var parent = host as ViewGroup
        val draggedView =
          if (event.localState is View) event.localState as View else null

        when (event.action) {
          DragEvent.ACTION_DRAG_STARTED -> {
            if (isEnableVibration) VibrateUtils.vibrate(100)
            if ((draggedView != null
                && !(draggedView is AdapterView<*> && parent is AdapterView<*>))
            ) parent.removeView(draggedView)
          }

          DragEvent.ACTION_DRAG_EXITED -> {
            removeWidget(shadow)
            updateUndoRedoHistory()
          }

          DragEvent.ACTION_DRAG_ENDED -> if (!event.result && draggedView != null) {
            if (selectedView == draggedView) {
              selectedView = null
              invalidate()
            }
            removeId(draggedView, draggedView is ViewGroup)
            removeViewAttributes(draggedView)
            viewAttributeMap.remove(draggedView)
            updateStructure()
          }

          DragEvent.ACTION_DRAG_LOCATION, DragEvent.ACTION_DRAG_ENTERED -> if (shadow.parent == null) addWidget(
            shadow,
            parent,
            event
          )
          else {
            if (parent is LinearLayout) {
              val index = parent.indexOfChild(shadow)
              val newIndex = getIndexForNewChildOfLinear(parent, event)

              if (index != newIndex) {
                parent.removeView(shadow)
                try {
                  parent.addView(shadow, newIndex)
                } catch (_: IllegalStateException) {
                }
              }
            } else {
              if (shadow.parent !== parent) addWidget(shadow, parent, event)
            }
          }

          DragEvent.ACTION_DROP -> {
            removeWidget(shadow)
            if (childCount >= 1) {
              if (getChildAt(0) !is ViewGroup) {
                Toast.makeText(
                  context,
                  "Can\'t add more than one widget in the editor.",
                  Toast.LENGTH_SHORT
                ).show()
                return@OnDragListener true
              } else {
                if (parent is DesignEditor) parent = getChildAt(0) as ViewGroup
              }
            }
            if (draggedView == null) {
              @Suppress("UNCHECKED_CAST") val data: HashMap<String, Any> =
                event.localState as HashMap<String, Any>
              val newView =
                InvokeUtil.createView(
                  data[Constants.KEY_CLASS_NAME].toString(), context
                ) as View

              newView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
              )
              rearrangeListeners(newView)

              if (newView is ViewGroup) {
                setDragListener(newView)
                setTransition(newView)
              }
              newView.minimumWidth = Utils.pxToDp(context, 20)
              newView.minimumHeight = Utils.pxToDp(context, 20)

              if (newView is EditText) newView.isFocusable = false

              val map = AttributeMap()
              val id = getIdForNewView(
                newView.javaClass.superclass.simpleName
                  .replace(" ".toRegex(), "_")
                  .lowercase()
              )
              IdManager.addNewId(newView, id)
              map.putValue("android:id", "@+id/$id")
              map.putValue("android:layout_width", "wrap_content")
              map.putValue("android:layout_height", "wrap_content")
              viewAttributeMap[newView] = map

              addWidget(newView, parent, event)

              try {
                val cls: Class<*> = newView.javaClass
                val setStrokeEnabled =
                  cls.getMethod("setStrokeEnabled", Boolean::class.javaPrimitiveType)
                val setBlueprint = cls.getMethod("setBlueprint", Boolean::class.javaPrimitiveType)
                setStrokeEnabled.invoke(newView, isShowStroke)
                setBlueprint.invoke(newView, isBlueprint)
              } catch (e: Exception) {
                e.printStackTrace()
              }

              if (data.containsKey(Constants.KEY_DEFAULT_ATTRS)) {
                @Suppress("UNCHECKED_CAST")
                initializer.applyDefaultAttributes(
                  newView, data[Constants.KEY_DEFAULT_ATTRS] as MutableMap<String, String>
                )
              }
            } else addWidget(draggedView, parent, event)
            updateStructure()
            updateUndoRedoHistory()
          }
        }
        true
      })
  }

  private fun getIdForNewView(name: String): String {
    var id = name
    var n = 0
    var firstTime = true
    while (IdManager.containsId(id)) {
      n++
      id = if (firstTime) "$name$n" else id.replace(
        id.elementAt(id.lastIndex).toString().toRegex(), n.toString()
      )
      firstTime = false
    }
    return id
  }

  fun loadLayoutFromParser(xml: String) {
    clearAll()

    if (xml.isEmpty()) return

    val parser = XmlLayoutParser(context)
    parser.parseFromXml(xml, context)
    addView(parser.root)
    viewAttributeMap = parser.viewAttributeMap

    for (view in viewAttributeMap.keys) {
      rearrangeListeners(view)

      if (view is ViewGroup) {
        setDragListener(view)
        setTransition(view)
      }
      view.minimumWidth = Utils.pxToDp(context, 20)
      view.minimumHeight = Utils.pxToDp(context, 20)
    }

    updateStructure()
    toggleStrokeWidgets()

    initializer =
      AttributeInitializer(context, viewAttributeMap, attributes, parentAttributes)
  }

  fun undo() {
    if (undoRedoManager == null) return
    if (undoRedoManager!!.isUndoEnabled) loadLayoutFromParser(undoRedoManager!!.undo())
  }

  fun redo() {
    if (undoRedoManager == null) return
    if (undoRedoManager!!.isRedoEnabled) loadLayoutFromParser(undoRedoManager!!.redo())
  }

  private fun clearAll() {
    selectedView = null
    activeHandle = -1
    removeAllViews()
    structureView!!.clear()
    viewAttributeMap.clear()
  }

  fun setStructureView(view: StructureView?) {
    structureView = view
  }

  fun bindUndoRedoManager(manager: UndoRedoManager?) {
    undoRedoManager = manager
  }

  private fun updateStructure() {
    if (childCount == 0) structureView!!.clear()
    else structureView!!.setView(getChildAt(0))
  }

  fun updateUndoRedoHistory() {
    if (undoRedoManager == null) return
    val result = XmlLayoutGenerator().generate(this, false)
    undoRedoManager!!.addToHistory(result)
  }

  private fun rearrangeListeners(view: View) {
    if (view is TextView) {
      view.setTextIsSelectable(false)
    }
    view.isLongClickable = false

    val gestureDetector =
      GestureDetector(
        context,
        object : SimpleOnGestureListener() {
          override fun onLongPress(event: MotionEvent) {
            try {
              view.startDragAndDrop(null, DragShadowBuilder(view), view, 0)
            } catch (_: Exception) {}
          }
        })

    view.setOnTouchListener(
      object : OnTouchListener {
        var bClick: Boolean = true
        var startX: Float = 0f
        var startY: Float = 0f
        var endX: Float = 0f
        var endY: Float = 0f
        var diffX: Float = 0f
        var diffY: Float = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
          when (event.action) {
            MotionEvent.ACTION_DOWN -> {
              startX = event.x
              startY = event.y
              bClick = true
            }

            MotionEvent.ACTION_UP -> {
              endX = event.x
              endY = event.y
              diffX = abs((startX - endX).toDouble()).toFloat()
              diffY = abs((startY - endY).toDouble()).toFloat()

              if ((diffX <= 5) && (diffY <= 5) && bClick) {
                selectView(v)
                showDefinedAttributes(v)
              }

              bClick = false
            }
          }
          gestureDetector.onTouchEvent(event)
          return true
        }
      })
  }

  private fun addWidget(view: View, newParent: ViewGroup, event: DragEvent) {
    removeWidget(view)
    if (newParent is LinearLayout) {
      val index = getIndexForNewChildOfLinear(newParent, event)
      newParent.addView(view, index)
    } else {
      try {
        newParent.addView(view, newParent.childCount)
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
  }

  private fun removeWidget(view: View) {
    (view.parent as ViewGroup?)?.removeView(view)
  }

  private fun getIndexForNewChildOfLinear(layout: LinearLayout, event: DragEvent): Int {
    val orientation = layout.orientation
    if (orientation == HORIZONTAL) {
      var index = 0
      for (i in 0 until layout.childCount) {
        val child = layout.getChildAt(i)
        if (child === shadow) continue
        if (child.right < event.x) index++
      }
      return index
    }
    if (orientation == VERTICAL) {
      var index = 0
      for (i in 0 until layout.childCount) {
        val child = layout.getChildAt(i)
        if (child === shadow) continue
        if (child.bottom < event.y) index++
      }
      return index
    }
    return -1
  }

  fun showDefinedAttributes(target: View) {
    val keys = viewAttributeMap[target]!!.keySet()
    val values = viewAttributeMap[target]!!.values()

    val attrs: MutableList<HashMap<String, Any>> = ArrayList()
    val allAttrs = initializer.getAllAttributesForView(target)

    val dialog = BottomSheetDialog(context)
    val binding =
      ShowAttributesDialogBinding.inflate(dialog.layoutInflater)

    dialog.setContentView(binding.root)
    TooltipCompat.setTooltipText(binding.btnAdd, "Add attribute")
    TooltipCompat.setTooltipText(binding.btnDelete, "Delete")

    for (key: String in keys) {
      for (map: HashMap<String, Any> in allAttrs) {
        if ((map[Constants.KEY_ATTRIBUTE_NAME].toString() == key)) {
          attrs.add(map)
          break
        }
      }
    }

    val appliedAttributesAdapter = AppliedAttributesAdapter(attrs, values)

    appliedAttributesAdapter.onClick = {
      showAttributeEdit(target, keys[it])
      dialog.dismiss()
    }

    appliedAttributesAdapter.onRemoveButtonClick = {
      dialog.dismiss()

      val view = removeAttribute(target, keys[it])
      showDefinedAttributes(view)
    }

    binding.attributesList.adapter = appliedAttributesAdapter
    binding.attributesList.layoutManager =
      LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    binding.viewName.text = target.javaClass.superclass.simpleName
    binding.viewFullName.text = target.javaClass.superclass.name
    binding.btnAdd.setOnClickListener {
      showAvailableAttributes(target)
      dialog.dismiss()
    }

    binding.btnDelete.setOnClickListener {
      MaterialAlertDialogBuilder(context)
        .setTitle(R.string.delete_view)
        .setMessage(R.string.msg_delete_view)
        .setNegativeButton(
          R.string.no
        ) { d, _ ->
          d.dismiss()
        }
        .setPositiveButton(
          R.string.yes
        ) { _, _ ->
          if (selectedView == target) {
            selectedView = null
            invalidate()
          }
          removeId(target, target is ViewGroup)
          removeViewAttributes(target)
          removeWidget(target)
          updateStructure()
          updateUndoRedoHistory()
          dialog.dismiss()
        }
        .show()
    }

    dialog.show()
  }

  private fun showAvailableAttributes(target: View) {
    val availableAttrs =
      initializer.getAvailableAttributesForView(target)
    val names: MutableList<String> = ArrayList()

    for (attr: HashMap<String, Any> in availableAttrs) {
      names.add(attr["name"].toString())
    }

    MaterialAlertDialogBuilder(context)
      .setTitle("Available attributes")
      .setAdapter(
        ArrayAdapter(context, android.R.layout.simple_list_item_1, names)
      ) { _, w ->
        showAttributeEdit(
          target, availableAttrs[w][Constants.KEY_ATTRIBUTE_NAME].toString()
        )
      }
      .show()
  }

  private fun showAttributeEdit(target: View, attributeKey: String) {
    val allAttrs = initializer.getAllAttributesForView(target)
    val currentAttr =
      initializer.getAttributeFromKey(attributeKey, allAttrs)
    val attributeMap = viewAttributeMap[target]

    val argumentTypes =
      currentAttr?.get(Constants.KEY_ARGUMENT_TYPE)?.toString()?.split("\\|".toRegex())
        ?.dropLastWhile { it.isEmpty() }
        ?.toTypedArray()

    if (argumentTypes != null) {
      if (argumentTypes.size > 1) {
        if (attributeMap!!.contains(attributeKey)) {
          val argumentType =
            parseType(attributeMap.getValue(attributeKey), argumentTypes)
          showAttributeEdit(target, attributeKey, argumentType)
          return
        }
        MaterialAlertDialogBuilder(context)
          .setTitle(R.string.select_arg_type)
          .setAdapter(
            ArrayAdapter(
              context, android.R.layout.simple_list_item_1, argumentTypes
            )
          ) { _, w ->
            showAttributeEdit(target, attributeKey, argumentTypes[w])
          }
          .show()

        return
      }
    }
    showAttributeEdit(target, attributeKey, argumentTypes?.get(0))
  }

  @Suppress("UNCHECKED_CAST")
  private fun showAttributeEdit(
    target: View, attributeKey: String, argumentType: String?
  ) {
    val allAttrs = initializer.getAllAttributesForView(target)
    val currentAttr =
      initializer.getAttributeFromKey(attributeKey, allAttrs)
    val attributeMap = viewAttributeMap[target]

    var savedValue =
      if (attributeMap!!.contains(attributeKey)) attributeMap.getValue(attributeKey) else ""
    val defaultValue =
      if (currentAttr?.containsKey(Constants.KEY_DEFAULT_VALUE) == true)
        currentAttr[Constants.KEY_DEFAULT_VALUE].toString()
      else null
    val constant =
      if (currentAttr?.containsKey(Constants.KEY_CONSTANT) == true
      ) currentAttr[Constants.KEY_CONSTANT].toString()
      else null

    val context = context

    var dialog: AttributeDialog? = null

    when (argumentType) {
      Constants.ARGUMENT_TYPE_SIZE -> dialog = SizeDialog(context, savedValue)
      Constants.ARGUMENT_TYPE_DIMENSION -> dialog =
        DimensionDialog(context, savedValue, currentAttr?.get("dimensionUnit")?.toString())

      Constants.ARGUMENT_TYPE_ID -> dialog = IdDialog(context, savedValue)
      Constants.ARGUMENT_TYPE_VIEW -> dialog = ViewDialog(context, savedValue, constant)
      Constants.ARGUMENT_TYPE_BOOLEAN -> dialog = BooleanDialog(context, savedValue)
      Constants.ARGUMENT_TYPE_DRAWABLE -> {
        if (savedValue.startsWith("@drawable/")) {
          savedValue = savedValue.replace("@drawable/", "")
        }
        dialog = StringDialog(context, savedValue, Constants.ARGUMENT_TYPE_DRAWABLE)
      }

      Constants.ARGUMENT_TYPE_STRING -> {
        if (savedValue.startsWith("@string/")) {
          savedValue = savedValue.replace("@string/", "")
        }
        dialog = StringDialog(context, savedValue, Constants.ARGUMENT_TYPE_STRING)
      }

      Constants.ARGUMENT_TYPE_TEXT -> dialog =
        StringDialog(context, savedValue, Constants.ARGUMENT_TYPE_TEXT)

      Constants.ARGUMENT_TYPE_INT -> dialog =
        NumberDialog(context, savedValue, Constants.ARGUMENT_TYPE_INT)

      Constants.ARGUMENT_TYPE_FLOAT -> dialog =
        NumberDialog(context, savedValue, Constants.ARGUMENT_TYPE_FLOAT)

      Constants.ARGUMENT_TYPE_FLAG -> dialog =
        FlagDialog(context, savedValue, currentAttr?.get("arguments") as ArrayList<String>?)

      Constants.ARGUMENT_TYPE_ENUM -> dialog =
        EnumDialog(context, savedValue, currentAttr?.get("arguments") as ArrayList<String>?)

      Constants.ARGUMENT_TYPE_COLOR -> dialog = ColorDialog(context, savedValue)
    }
    if (dialog == null) return

    dialog.setTitle(currentAttr?.get("name")?.toString())
    dialog.setOnSaveValueListener {
      if (defaultValue != null && (defaultValue == it)) {
        if (attributeMap.contains(attributeKey)) removeAttribute(target, attributeKey)
      } else {
        if (currentAttr != null) {
          initializer.applyAttribute(target, it!!, currentAttr)
        }
        showDefinedAttributes(target)
        updateUndoRedoHistory()
        updateStructure()
      }
    }

    dialog.show()
  }

  private fun removeViewAttributes(view: View) {
    viewAttributeMap.remove(view)
    if (view is ViewGroup) {
      for (i in 0 until view.childCount) {
        removeViewAttributes(view.getChildAt(i))
      }
    }
  }

  private fun removeAttribute(target: View, attributeKey: String): View {
    @Suppress("NAME_SHADOWING")
    var target = target
    val allAttrs = initializer.getAllAttributesForView(target)
    val currentAttr =
      initializer.getAttributeFromKey(attributeKey, allAttrs)

    val attributeMap = viewAttributeMap[target]

    if (currentAttr != null) {
      if (currentAttr.containsKey(Constants.KEY_CAN_DELETE)) return target
    }

    val name =
      if (attributeMap!!.contains("android:id")) attributeMap.getValue("android:id") else null
    val id = if (name != null) getViewId(name.replace("@+id/", "")) else -1
    attributeMap.removeValue(attributeKey)

    if ((attributeKey == "android:id")) {
      removeId(target, false)
      target.id = -1
      target.requestLayout()

      // delete all id attributes for views
      for (view: View in viewAttributeMap.keys) {
        val map = viewAttributeMap[view]

        for (key: String in map!!.keySet()) {
          val value = map.getValue(key)

          if (value.startsWith("@id/") && (value == name!!.replace("+", ""))) map.removeValue(key)
        }
      }
      updateStructure()
      return target
    }

    viewAttributeMap.remove(target)

    val parent = target.parent as ViewGroup
    val indexOfView = parent.indexOfChild(target)

    parent.removeView(target)

    val childs: MutableList<View> = ArrayList()

    if (target is ViewGroup) {
      val group = target

      if (group.childCount > 0) {
        for (i in 0 until group.childCount) {
          childs.add(group.getChildAt(i))
        }
      }

      group.removeAllViews()
    }

    if (name != null) removeId(target, false)

    target = InvokeUtil.createView(target.javaClass.name, context) as View
    rearrangeListeners(target)

    if (target is ViewGroup) {
      target.setMinimumWidth(Utils.pxToDp(context, 20))
      target.setMinimumHeight(Utils.pxToDp(context, 20))
      val group = target
      if (childs.size > 0) {
        for (i in childs.indices) {
          group.addView(childs[i])
        }
      }
      setTransition(group)
    }

    parent.addView(target, indexOfView)
    viewAttributeMap[target] = attributeMap

    if (name != null) {
      addId(target, name, id)
      target.requestLayout()
    }

    val keys = attributeMap.keySet()
    val values = attributeMap.values()
    val attrs: MutableList<HashMap<String, Any>> = ArrayList()

    for (key: String in keys) {
      for (map: HashMap<String, Any> in allAttrs) {
        if ((map[Constants.KEY_ATTRIBUTE_NAME].toString() == key)) {
          attrs.add(map)
          break
        }
      }
    }

    for (i in keys.indices) {
      val key = keys[i]
      if ((key == "android:id")) continue
      initializer.applyAttribute(target, values[i], attrs[i])
    }

    try {
      val cls: Class<*> = target.javaClass
      val method = cls.getMethod("setStrokeEnabled", Boolean::class.javaPrimitiveType)
      method.invoke(target, isShowStroke)
    } catch (e: Exception) {
      e.printStackTrace()
    }
    updateStructure()
    updateUndoRedoHistory()
    return target
  }

  private fun initAttributes() {
    attributes = convertJsonToJavaObject(Constants.ATTRIBUTES_FILE)
    parentAttributes = convertJsonToJavaObject(Constants.PARENT_ATTRIBUTES_FILE)
    viewAttributeMap = HashMap()
    initializer =
      AttributeInitializer(context, viewAttributeMap, attributes, parentAttributes)
  }

  private fun convertJsonToJavaObject(filePath: String): HashMap<String, List<HashMap<String, Any>>> {
    return Gson()
      .fromJson(
        FileUtil.readFromAsset(filePath, context),
        object : TypeToken<HashMap<String?, ArrayList<HashMap<String?, Any?>?>?>?>() {}.type
      )
  }

  enum class ViewType {
    DESIGN,
    BLUEPRINT
  }
}
