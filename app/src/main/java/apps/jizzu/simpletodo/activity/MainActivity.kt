package apps.jizzu.simpletodo.activity

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import apps.jizzu.simpletodo.R
import apps.jizzu.simpletodo.alarm.AlarmHelper
import apps.jizzu.simpletodo.alarm.AlarmReceiver
import apps.jizzu.simpletodo.database.DBHelper
import apps.jizzu.simpletodo.model.ModelTask
import apps.jizzu.simpletodo.recycler.RecyclerViewAdapter
import apps.jizzu.simpletodo.recycler.RecyclerViewEmptySupport
import apps.jizzu.simpletodo.recycler.RecyclerViewItemTouchHelper
import apps.jizzu.simpletodo.recycler.RecyclerViewScrollListener
import apps.jizzu.simpletodo.settings.activity.SettingsActivity
import apps.jizzu.simpletodo.utils.Interpolator
import apps.jizzu.simpletodo.utils.MyApplication
import apps.jizzu.simpletodo.utils.PreferenceHelper
import apps.jizzu.simpletodo.widget.WidgetProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.miguelcatalan.materialsearchview.MaterialSearchView
import hotchemi.android.rate.AppRate
import kotlinx.android.synthetic.main.activity_main.*
import kotterknife.bindView
import top.wefor.circularanim.CircularAnim
import java.util.*
import kotlin.collections.ArrayList


class MainActivity : AppCompatActivity(), RecyclerViewAdapter.AdapterCallback {

    private val mRecyclerView: RecyclerViewEmptySupport by bindView(R.id.tasksList)
    private val mEmptyView: RelativeLayout by bindView(R.id.empty)
    private val mSearchView: MaterialSearchView by bindView(R.id.search_view)
    private val mFab: FloatingActionButton by bindView(R.id.fab)

    private lateinit var mAdapter: RecyclerViewAdapter
    private lateinit var mHelper: DBHelper
    private lateinit var mPreferenceHelper: PreferenceHelper
    private lateinit var mNotificationManager: NotificationManager
    private lateinit var mLayoutManager: androidx.recyclerview.widget.RecyclerView.LayoutManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = ContextCompat.getColor(this, R.color.grey_white)
        }

        mSearchViewIsOpen = false
        title = ""
        mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Initialize ALARM_SERVICE
        AlarmHelper.getInstance().init(applicationContext)

        if (toolbar != null) {
            toolbar.setTitleTextColor(ContextCompat.getColor(this, R.color.white))
            setSupportActionBar(toolbar)
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            supportActionBar!!.setHomeAsUpIndicator(R.drawable.ic_settings_black_24)
        }

        PreferenceHelper.getInstance().init(applicationContext)
        mPreferenceHelper = PreferenceHelper.getInstance()

        RecyclerViewEmptySupport(this)
        mRecyclerView.setHasFixedSize(true)

        mLayoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        mRecyclerView.layoutManager = mLayoutManager
        mAdapter = RecyclerViewAdapter.getInstance()
        mRecyclerView.adapter = mAdapter
        mRecyclerView.setEmptyView(mEmptyView)

        mAdapter.registerCallback(this)

        val callback = object : RecyclerViewItemTouchHelper(mAdapter, mRecyclerView) {
            override fun onMove(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, target: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean {
                super.onMove(recyclerView, viewHolder, target)
                updateGeneralNotification()
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {
                super.onSwiped(viewHolder, direction)
                updateGeneralNotification()
            }
        }
        val touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(mRecyclerView)

        mHelper = DBHelper.getInstance(this)
        addTasksFromDB()

        // Show rate this app dialog
        AppRate.with(this).setInstallDays(0).setLaunchTimes(5).setRemindInterval(3).monitor()
        AppRate.showRateDialogIfMeetsConditions(this)

        mSearchView.setOnQueryTextListener(object : MaterialSearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                Log.d(TAG, "onQueryTextSubmit")
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                findTasks(newText)
                Log.d(TAG, "onQueryTextChange: newText = $newText")
                return false
            }
        })

        mSearchView.setOnSearchViewListener(object : MaterialSearchView.SearchViewListener {
            override fun onSearchViewShown() {
                Log.d(TAG, "onSearchViewShown!")
                mSearchViewIsOpen = true
                mFab.hide()
                mFab.isEnabled = false
                Log.d(TAG, "isSearchOpen = $mSearchViewIsOpen")
            }

            override fun onSearchViewClosed() {
                Log.d(TAG, "onSearchViewClosed!")
                addTasksFromDB()
                startEmptyViewAnimation()
                mSearchViewIsOpen = false
                mShowAnimation = false

                val handler = Handler()
                handler.postDelayed({
                    mFab.show()
                    mFab.isEnabled = true
                }, 500)
                Log.d(TAG, "isSearchOpen = $mSearchViewIsOpen")
            }
        })

        mFab.setOnClickListener { view ->
            if (mPreferenceHelper.getBoolean(PreferenceHelper.ANIMATION_IS_ON)) {
                CircularAnim.fullActivity(this@MainActivity, view)
                        .colorOrImageRes(R.color.colorPrimary)
                        .duration(300)
                        .go {
                            val intent = Intent(this@MainActivity, AddTaskActivity::class.java)

                            // Method startActivityForResult(Intent, int) allows to get the right data (Title for RecyclerView item for example) from another activity.
                            // To obtain data from the activity used onActivityResult(int, int, Intent) method that is called when the AddTaskActivity completes it's work.
                            startActivityForResult(intent, 1)
                        }
            } else {
                val intent = Intent(this@MainActivity, AddTaskActivity::class.java)
                startActivityForResult(intent, 1)
            }
        }

        if (mPreferenceHelper.getBoolean(PreferenceHelper.ANIMATION_IS_ON)) {
            (mFab as View).visibility = View.GONE

            // Starts the RecyclerView items animation
            val resId = R.anim.layout_animation
            val animation = AnimationUtils.loadLayoutAnimation(this, resId)
            mRecyclerView.layoutAnimation = animation
        } else {
            (mFab as View).visibility = View.VISIBLE
        }


        mRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && mFab.visibility == View.VISIBLE) {
                    mFab.hide()
                } else if (dy < 0 && mFab.visibility != View.VISIBLE && !mSearchViewIsOpen) {
                    mFab.show()
                }
            }
        })

        mRecyclerView.addOnScrollListener(object : RecyclerViewScrollListener() {
            override fun onShow() {
                setToolbarShadow(0f, 30f)
            }

            override fun onHide() {
                setToolbarShadow(30f, 0f)
            }
        })
    }

    private fun setToolbarShadow(start: Float, end: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            ValueAnimator.ofFloat(start, end).apply {
                addUpdateListener { updatedAnimation ->
                    toolbar.elevation = updatedAnimation.animatedValue as Float
                }
                duration = 500
                start()
            }
        }
    }

    /**
     * Finds tasks by the title in the database.
     */
    private fun findTasks(title: String) {
        mSearchViewIsOpen = true
        Log.d(TAG, "findTasks: SearchView Title = $title")
        mAdapter.removeAllTasks()
        val tasks = ArrayList<ModelTask>()

        if (title != "") {
            tasks.addAll(mHelper.getTasksForSearch(DBHelper.SELECTION_LIKE_TITLE, arrayOf("%$title%"), DBHelper.TASK_DATE_COLUMN))
        } else {
            tasks.addAll(mHelper.getAllTasks())
        }

        for (i in tasks.indices) {
            mAdapter.addTask(tasks[i])
        }
    }

    /**
     * Reads all tasks from the database and adds them to the RecyclerView list.
     */
    private fun addTasksFromDB() {
        mAdapter.removeAllTasks()
        val taskList = mHelper.getAllTasks()

        for (task in taskList) {
            mAdapter.addTask(task, task.position)
        }
    }

    /**
     * Starts the EmptyView animation.
     */
    private fun startEmptyViewAnimation() {
        if (mAdapter.itemCount == 0 && mShowAnimation) {
            mSearchViewIsOpen = false
            mRecyclerView.checkIfEmpty()
        }
    }

    /**
     * Updates widget data.
     */
    private fun updateWidget() {
        Log.d(TAG, "WIDGET IS UPDATED!")
        val intent = Intent(this, WidgetProvider::class.java)
        intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        val ids = AppWidgetManager.getInstance(this@MainActivity)
                .getAppWidgetIds(ComponentName(this@MainActivity, WidgetProvider::class.java))
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        sendBroadcast(intent)
    }

    /**
     * Updates general notification data.
     */
    private fun updateGeneralNotification() {
        if (mPreferenceHelper.getBoolean(PreferenceHelper.GENERAL_NOTIFICATION_IS_ON)) {
            if (mAdapter.itemCount != 0) {
                showGeneralNotification()
            } else {
                removeGeneralNotification()
            }
        } else {
            removeGeneralNotification()
        }
    }

    /**
     * Set up and show general notification.
     */
    private fun showGeneralNotification() {
        val stringBuilder = StringBuilder()

        val resultIntent = Intent(this, MainActivity::class.java)
        val resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        for (task in RecyclerViewAdapter.mTaskList) {
            stringBuilder.append("• ").append(task.title)

            if (task.position < mAdapter.itemCount - 1) {
                stringBuilder.append("\n\n")
            }
        }

        var notificationTitle = ""
        when (mAdapter.itemCount % 10) {
            1 -> notificationTitle = getString(R.string.general_notification_1) + " " + mAdapter.itemCount + " " + getString(R.string.general_notification_2)

            2, 3, 4 -> notificationTitle = getString(R.string.general_notification_1) + " " + mAdapter.itemCount + " " + getString(R.string.general_notification_3)

            0, 5, 6, 7, 8, 9 -> notificationTitle = getString(R.string.general_notification_1) + " " + mAdapter.itemCount + " " + getString(R.string.general_notification_4)
        }

        // Set NotificationChannel for Android Oreo
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(AlarmReceiver.GENERAL_NOTIFICATION_CHANNEL_ID, getString(R.string.general_notification_channel),
                    NotificationManager.IMPORTANCE_LOW)
            channel.enableLights(false)
            channel.enableVibration(false)
            mNotificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, AlarmReceiver.GENERAL_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setContentText(stringBuilder.toString())
                .setNumber(mAdapter.itemCount)
                .setStyle(NotificationCompat.BigTextStyle().bigText(stringBuilder.toString()))
                .setColor(ContextCompat.getColor(this, R.color.colorAccent))
                .setSmallIcon(R.drawable.ic_check_circle_white_24dp)
                .setContentIntent(resultPendingIntent)
                .setOngoing(true)

        val notification = builder.build()
        mNotificationManager.notify(1, notification)
    }

    /**
     * Removes general notification.
     */
    private fun removeGeneralNotification() = mNotificationManager.cancel(1)

    /**
     * Updates general notification data when user click the "Cancel" snackbar button.
     */
    override fun updateData() = updateGeneralNotification()

    override fun showFAB() = mFab.show()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)

        val item = menu.findItem(R.id.action_search)
        mSearchView.setMenuItem(item)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) =
            if (item.itemId == android.R.id.home) {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            } else false

    /**
     * Reads all tasks from the database and compares them with mTaskList in RecyclerView.
     * If the tasks count in the database doesn't coincide with the number of tasks in RecyclerView,
     * all the tasks in the database are replaced with tasks from the mTaskList.
     * For example, this happens when the user removes the task from the RecyclerView list and hide/close app until the snackbar has disappeared.
     */
    override fun onStop() {
        super.onStop()

        Log.d(TAG, "onStop call!!!")
        val taskList = mHelper.getAllTasks()

        Log.d(TAG, "dbSize = ${taskList.size}, adapterSize = ${RecyclerViewAdapter.mTaskList.size}")
        if (taskList.size != RecyclerViewAdapter.mTaskList.size && !mSearchViewIsOpen && !mActivityIsShown) {

            mHelper.deleteAllTasks()

            for (task in RecyclerViewAdapter.mTaskList) {
                mHelper.saveTask(task)
            }
            mActivityIsShown = false
        }
        if (!mFab.isShown && !mSearchViewIsOpen) {
            mFab.show()
        }
        updateWidget()
    }

    override fun onResume() {
        super.onResume()

        (mFab as View).visibility = View.GONE
        Log.d(TAG, "onResume call!!!")

        if (!mSearchViewIsOpen) {
            if (mPreferenceHelper.getBoolean(PreferenceHelper.ANIMATION_IS_ON)) {
                // Starts the FAB animation
                val handler = Handler()
                handler.postDelayed({
                    (mFab as View).visibility = View.VISIBLE
                    val myAnim = AnimationUtils.loadAnimation(this, R.anim.fab_animation)
                    val interpolator = Interpolator(0.2, 20.0)
                    myAnim.interpolator = interpolator
                    mFab.startAnimation(myAnim)
                }, 300)
            } else {
                (mFab as View).visibility = View.VISIBLE
            }
        }
        MyApplication.activityResumed()
        updateGeneralNotification()
    }

    override fun onPause() {
        super.onPause()
        MyApplication.activityPaused()
    }

    /**
     * Called when an activity you launched exits, giving you the requestCode you started it with, the resultCode it returned, and any additional data from it.
     * requestCode: The integer request code originally supplied to startActivityForResult(), allowing you to identify who this result came from.
     * resultCode: The integer result code returned by the child activity through its setResult().
     * data: An Intent, which can return result data to the caller (various data can be attached to Intent "extras").
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (data == null) return

        val taskTitle = data.getStringExtra("title")
        val taskDate = data.getLongExtra("date", 0)

        val task = ModelTask()
        task.title = taskTitle
        task.date = taskDate
        task.position = mAdapter.itemCount

        // Set notification to the current task
        if (task.date != 0L && task.date <= Calendar.getInstance().timeInMillis) {
            Toast.makeText(this, getString(R.string.toast_incorrect_time), Toast.LENGTH_SHORT).show()
            task.date = 0
        } else if (task.date != 0L) {
            val alarmHelper = AlarmHelper.getInstance()
            alarmHelper.setAlarm(task)
        }

        val id = mHelper.saveTask(task)
        task.id = id
        mAdapter.addTask(task)
        updateGeneralNotification()
    }

    override fun onBackPressed() = if (mSearchView.isSearchOpen) mSearchView.closeSearch()
        else super.onBackPressed()

    companion object {
        var mSearchViewIsOpen: Boolean = false
        var mShowAnimation: Boolean = false
        var mActivityIsShown: Boolean = false
    }
}
