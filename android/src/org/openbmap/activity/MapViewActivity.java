/*
	Radiobeacon - Openbmap wifi and cell logger
    Copyright (C) 2013  wish7

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.openbmap.activity;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

import org.mapsforge.core.graphics.Color;
import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.map.android.AndroidPreferences;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.Layer;
import org.mapsforge.map.layer.LayerManager;
import org.mapsforge.map.layer.Layers;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Circle;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.model.MapViewPosition;
import org.mapsforge.map.model.common.Observer;
import org.mapsforge.map.util.MapPositionUtil;
import org.openbmap.Preferences;
import org.openbmap.R;
import org.openbmap.RadioBeacon;
import org.openbmap.db.DataHelper;
import org.openbmap.db.Schema;
import org.openbmap.db.model.Session;
import org.openbmap.db.model.WifiRecord;
import org.openbmap.utils.GpxMapObjectsLoader;
import org.openbmap.utils.GpxMapObjectsLoader.OnGpxLoadedListener;
import org.openbmap.utils.LatLongHelper;
import org.openbmap.utils.MapUtils;
import org.openbmap.utils.SessionMapObjectsLoader;
import org.openbmap.utils.SessionMapObjectsLoader.OnSessionLoadedListener;
import org.openbmap.utils.WifiCatalogMapObjectsLoader;
import org.openbmap.utils.WifiCatalogMapObjectsLoader.OnCatalogLoadedListener;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.location.Location;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.ToggleButton;

/**
 * Activity for displaying session's GPX track and wifis
 */
public class MapViewActivity extends Activity implements
OnCatalogLoadedListener,
OnSessionLoadedListener,
OnGpxLoadedListener {

	private static final String TAG = MapViewActivity.class.getSimpleName();

	
	
	/**
	 * If zoom level < MIN_OBJECT_ZOOM session wifis and wifi catalog objects won't be displayed for performance reasons
	 */
	private static final int	MIN_OBJECT_ZOOM	= 12;

	private static final Color	COLOR_GPX	= Color.BLACK;

	private static final int ALPHA_WIFI_CATALOG_FILL	= 90;
	private static final int ALPHA_WIFI_CATALOG_STROKE	= 100;

	private static final int ALPHA_SESSION_FILL	= 50;
	private static final int ALPHA_SESSION_STROKE	= 100;

	private static final int CIRCLE_WIFI_CATALOG_WIDTH = 20;	
	private static final int CIRCLE_SESSION_WIDTH = 30;	
	private static final int STROKE_GPX_WIDTH = 5;

	/**
	 * Keeps the SharedPreferences.
	 */
	private SharedPreferences prefs = null;

	/**
	 * Database helper for retrieving session wifi scan results.
	 */
	private DataHelper dbHelper;

	/**
	 * 	Minimum time (in millis) between automatic overlay refresh
	 */
	protected static final float SESSION_REFRESH_INTERVAL = 2000;

	/**
	 * Minimum distance (in meter) between automatic session overlay refresh
	 */
	protected static final float SESSION_REFRESH_DISTANCE = 10;


	/**
	 * Minimum distance (in meter) between automatic catalog overlay refresh
	 * Please note: catalog objects are static, thus updates aren't necessary that often
	 */
	protected static final float CATALOG_REFRESH_DISTANCE = 200;


	/**
	 * 	Minimum time (in millis) between automatic catalog refresh
	 * 	Please note: catalog objects are static, thus updates aren't necessary that often
	 */
	protected static final float CATALOG_REFRESH_INTERVAL = 5000;

	/**
	 * 	Minimum time (in millis) between gpx position refresh
	 */
	protected static final float GPX_REFRESH_INTERVAL = 1000;

	/**
	 * Load more than currently visible objects?
	 */
	private static final boolean PREFETCH_MAP_OBJECTS	= true;

	/**
	 * Session currently displayed
	 */
	private int sessionId;

	/**
	 * System time of last gpx refresh (in millis)
	 */
	private long gpxRefreshTime;

	private AndroidPreferences	preferencesFacade;

	private byte	lastZoom;

	// [start] UI controls
	/**
	 * MapView
	 */
	private MapView mMapView;

	/**
	 * When checked map view will automatically focus current location
	 */
	private ToggleButton btnSnapToLocation;

	private ImageButton	btnZoom;

	private ImageButton	btnUnzoom;
	//[end]

	// [start] Map styles
	/**
	 * Baselayer cache
	 */
	private TileCache tileCache;

	private Paint paintCatalogFill;

	private Paint paintCatalogStroke;

	private Paint paintSessionFill;

	private ArrayList<Layer>	catalogObjects;

	private ArrayList<Layer>	sessionObjects;

	private Polyline	gpxObjects;
	//[end]

	// [start] Dynamic map variables
	/**
	 * Another wifi catalog overlay refresh is taking place
	 */
	private boolean	mRefreshCatalogPending = false;

	/**
	 * Another session overlay refresh is taking place
	 */
	private boolean	mRefreshSessionPending = false;

	/**
	 * Direction marker is currently updated
	 */
	private boolean	mRefreshDirectionPending;

	/**
	 * Another gpx overlay refresh is taking place
	 */
	private boolean	mRefreshGpxPending;

	/**
	 * System time of last session overlay refresh (in millis)
	 */
	private long	sessionObjectsRefreshTime;

	/**
	 * System time of last catalog overlay refresh (in millis)
	 */
	private long	catalogObjectsRefreshTime;

	/**
	 * Location of last session overlay refresh
	 */
	private Location sessionObjectsRefreshedAt = new Location("DUMMY");

	/**
	 * Location of last session overlay refresh
	 */
	private Location catalogObjectsRefreshedAt = new Location("DUMMY");
	// [end]

	/**
	 * Receives GPS location updates.
	 */
	private BroadcastReceiver mReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(final Context context, final Intent intent) {
			// handling GPS broadcasts
			if (RadioBeacon.INTENT_BROADCAST_POSITION.equals(intent.getAction())) {
				Location location = intent.getExtras().getParcelable("android.location.Location");

				// if btnSnapToLocation is checked, move map
				if (btnSnapToLocation.isChecked() && mMapView != null) {
					LatLong currentPos = new LatLong(location.getLatitude(), location.getLongitude());
					mMapView.getModel().mapViewPosition.setCenter(currentPos);
				}
				
				// update overlays
				if (LatLongHelper.isValidLocation(location)) {
					/*
					 * Update overlays if necessary, but only if
					 * 1.) current zoom level >= 12 (otherwise single points not visible, huge performance impact)
					 * 2.) overlay items haven't been refreshed for a while AND user has moved a bit
					 */
					if ((mMapView.getModel().mapViewPosition.getZoomLevel() >= MIN_OBJECT_ZOOM) && (needSessionObjectsRefresh(location))) {	
						refreshSessionOverlays(location);
					}

					if ((mMapView.getModel().mapViewPosition.getZoomLevel() >= MIN_OBJECT_ZOOM) && (needCatalogObjectsRefresh(location))) {	
						refreshCatalogOverlay(location);
					}

					if (needGpxRefresh()) {	
						refreshGpxTrace(location);
					}

					// indicate bearing
					refreshCompass(location);

				} else {
					Log.e(TAG, "Invalid positon! Cycle skipped");
				}

				location = null;
			} 
		}
	};

	@Override
	public final void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.mapview);

		// get shared preferences
		prefs = PreferenceManager.getDefaultSharedPreferences(this);

		SharedPreferences sharedPreferences = this.getSharedPreferences(getPersistableId(), MODE_PRIVATE);
		preferencesFacade = new AndroidPreferences(sharedPreferences);

		// Register our gps broadcast mReceiver
		registerReceiver();

		initUi();

		catalogObjects = new ArrayList<Layer>();
		sessionObjects = new ArrayList<Layer>();
		gpxObjects = new Polyline(MapUtils.createPaint(AndroidGraphicFactory.INSTANCE.createColor(Color.BLACK), STROKE_GPX_WIDTH,
				Style.STROKE), AndroidGraphicFactory.INSTANCE);
	}

	@Override
	protected final void onResume() {
		super.onResume();


		initDb();

		initMap();
		registerReceiver();

		if (this.getIntent().hasExtra(Schema.COL_ID)) {
			int focusWifi = this.getIntent().getExtras().getInt(Schema.COL_ID);
			Log.d(TAG, "Zooming onto " + focusWifi);
			if (focusWifi != 0) {
				loadSingleObject(focusWifi);
			}
		}
	}

	private void initDb() {
		dbHelper = new DataHelper(this);

		Session tmp = dbHelper.loadActiveSession();
		if (tmp != null) {
			sessionId = tmp.getId();
			Log.i(TAG, "Displaying session " + sessionId);
			tmp = null;
		} else {
			Log.w(TAG, "No active session?");
			sessionId = RadioBeacon.SESSION_NOT_TRACKING;
		}
	}

	/**
	 * Initializes map components
	 */
	private void initMap() {
		mMapView = (MapView) findViewById(R.id.map);
		mMapView.getModel().init(preferencesFacade);
		mMapView.setClickable(true);
		mMapView.getMapScaleBar().setVisible(true);

		this.tileCache = createTileCache();

		LayerManager layerManager = this.mMapView.getLayerManager();
		Layers layers = layerManager.getLayers();

		MapViewPosition mapViewPosition = this.mMapView.getModel().mapViewPosition;

		//mapViewPosition.setZoomLevel((byte) 16);
		//mapViewPosition.setCenter(this.dummyItem.location);

		layers.add(MapUtils.createTileRendererLayer(this.tileCache, mapViewPosition, getMapFile()));

		Observer observer = new Observer() {
			@Override
			public void onChange() {

				byte zoom = mMapView.getModel().mapViewPosition.getZoomLevel();
				if (zoom != lastZoom && zoom >= MIN_OBJECT_ZOOM) {
					// update overlays on zoom level changed
					Log.i(TAG, "New zoom level " + zoom + ", reloading map objects");
					Location mapCenter = new Location("DUMMY");
					mapCenter.setLatitude(mMapView.getModel().mapViewPosition.getCenter().latitude);
					mapCenter.setLongitude(mMapView.getModel().mapViewPosition.getCenter().longitude);
					refreshSessionOverlays(mapCenter);
					refreshCatalogOverlay(mapCenter);

					lastZoom = zoom;
				}

				if (!btnSnapToLocation.isChecked()) {
					// update overlay in free-move mode
					LatLong tmp = mMapView.getModel().mapViewPosition.getCenter();
					Location position = new Location("DUMMY");
					position.setLatitude(tmp.latitude);
					position.setLongitude(tmp.longitude);

					if (needSessionObjectsRefresh(position)) {
						refreshSessionOverlays(position);
					}

					if (needCatalogObjectsRefresh(position)) {
						refreshCatalogOverlay(position);
					}
				}

			}
		};
		mMapView.getModel().mapViewPosition.addObserver(observer);

		/*
				mWifiCatalogOverlay = new ListOverlay();
				mSessionOverlay = new ListOverlay();
				mGpxOverlay = new ListOverlay();

				mMapView.getOverlays().add(mWifiCatalogOverlay);
				mMapView.getOverlays().add(mSessionOverlay);
				mMapView.getOverlays().add(mGpxOverlay);
		 */
	}

	@Override
	protected final void onPause() {
		releaseMap();
		unregisterReceiver();

		super.onPause();
	}

	@Override
	protected final void onDestroy() {
		releaseMap();
		super.onDestroy();
	}

	/**
	 * Initializes UI componensts
	 */
	private void initUi() {
		btnSnapToLocation = (ToggleButton) findViewById(R.id.mapview_tb_snap_to_location);
		btnZoom = (ImageButton) findViewById(R.id.btnZoom);		
		btnZoom.setOnClickListener(new OnClickListener() {	
					@Override
					public void onClick(final View v) {
						byte zoom  = mMapView.getModel().mapViewPosition.getZoomLevel();
						if (zoom < mMapView.getModel().mapViewPosition.getZoomLevelMax()) {
							mMapView.getModel().mapViewPosition.setZoomLevel((byte) (zoom + 1));
						}
					}
				});
	
		btnUnzoom = (ImageButton) findViewById(R.id.btnUnzoom);
		btnUnzoom.setOnClickListener(new OnClickListener() {	
			@Override
			public void onClick(final View v) {
				byte zoom  = mMapView.getModel().mapViewPosition.getZoomLevel();
				if (zoom > mMapView.getModel().mapViewPosition.getZoomLevelMin()) {
					mMapView.getModel().mapViewPosition.setZoomLevel((byte) (zoom - 1));
				}
			}
		});
		paintCatalogFill = MapUtils.createPaint(AndroidGraphicFactory.INSTANCE.createColor(ALPHA_WIFI_CATALOG_FILL, 120, 150, 120), 2, Style.FILL);
		paintCatalogStroke = MapUtils.createPaint(AndroidGraphicFactory.INSTANCE.createColor(ALPHA_WIFI_CATALOG_STROKE, 120, 150, 120), 2, Style.STROKE);
		paintSessionFill = MapUtils.createPaint(AndroidGraphicFactory.INSTANCE.createColor(ALPHA_SESSION_FILL, 0, 0, 255), 2, Style.FILL);
	}

	private void registerReceiver() {
		IntentFilter filter = new IntentFilter();
		filter.addAction(RadioBeacon.INTENT_BROADCAST_POSITION);
		registerReceiver(mReceiver, filter);		
	}

	/**
	 * Unregisters receivers for GPS and wifi scan results.
	 */
	private void unregisterReceiver() {
		try {
			unregisterReceiver(mReceiver);
		} catch (IllegalArgumentException e) {
			// do nothing here {@see http://stackoverflow.com/questions/2682043/how-to-check-if-mReceiver-is-registered-in-android}
			return;
		}
	}

	protected final void refreshCatalogOverlay(final Location location) {
		if (!mRefreshCatalogPending) {
			Log.d(TAG, "Updating wifi catalog overlay");
			mRefreshCatalogPending = true;
			proceedAfterCatalogLoaded();
			catalogObjectsRefreshedAt = location;
			catalogObjectsRefreshTime = System.currentTimeMillis();
		} else {
			Log.v(TAG, "Reference overlay refresh in progress. Skipping refresh..");
		}
	}

	/**
	 * Is new location far enough from last refresh location?
	 * @return true if catalog overlay needs refresh
	 */
	private boolean needCatalogObjectsRefresh(final Location current) {
		if (current == null) { 
			// fail safe: draw if something went wrong
			return true;
		}

		return (
				(catalogObjectsRefreshedAt.distanceTo(current) > CATALOG_REFRESH_DISTANCE)
				&& ((System.currentTimeMillis() - catalogObjectsRefreshTime) > CATALOG_REFRESH_INTERVAL)
				);
	}

	/**
	 * Loads reference wifis around location from openbmap wifi catalog.
	 * Callback function, upon completion onCatalogLoaded is called for drawing
	 * @param center
	 * 			For performance reasons only wifis around specified location are displayed.
	 */
	private void proceedAfterCatalogLoaded() {

		BoundingBox bbox = MapPositionUtil.getBoundingBox(
				mMapView.getModel().mapViewPosition.getMapPosition(),
				mMapView.getDimension());

		double minLatitude = bbox.minLatitude;
		double maxLatitude = bbox.maxLatitude;
		double minLongitude = bbox.minLongitude;
		double maxLongitude = bbox.maxLongitude;

		// query more than visible objects for smoother data scrolling / less database queries?
		if (PREFETCH_MAP_OBJECTS) {
			double latSpan = maxLatitude - minLatitude;
			double lonSpan = maxLongitude - minLongitude;
			minLatitude -= latSpan * 0.5;
			maxLatitude += latSpan * 0.5;
			minLongitude -= lonSpan * 0.5;
			maxLongitude += lonSpan * 0.5;
		}
		WifiCatalogMapObjectsLoader task = new WifiCatalogMapObjectsLoader(this);
		task.execute(minLatitude, maxLatitude, minLongitude, maxLongitude);

	}

	/* (non-Javadoc)
	 * @see org.openbmap.utils.WifiCatalogMapObjectsLoader.OnCatalogLoadedListener#onComplete(java.util.ArrayList)
	 */
	@Override
	public final void onCatalogLoaded(final ArrayList<LatLong> points) {
		Log.d(TAG, "Loaded catalog objects");
		Layers layers = this.mMapView.getLayerManager().getLayers();


		// clear overlay
		for (Iterator<Layer> iterator = catalogObjects.iterator(); iterator.hasNext();) {
			Layer layer = (Layer) iterator.next();
			this.mMapView.getLayerManager().getLayers().remove(layer);
		}
		catalogObjects.clear();

		// redraw
		for (int i = 0; i < points.size(); i++) {
			Circle circle = new Circle(points.get(i), CIRCLE_WIFI_CATALOG_WIDTH, paintCatalogFill, paintCatalogStroke);
			catalogObjects.add(circle);
		}		

		int insertAfter = -1;
		if (layers.size() > 0) {
			// map 
			insertAfter = 1;
		} else {
			// no map 
			insertAfter = 0;
		}

		for (int i = 0; i < catalogObjects.size(); i++) {
			layers.add(insertAfter + i, catalogObjects.get(i));
		}		

		// enable next refresh
		mRefreshCatalogPending = false;
		Log.d(TAG, "Drawed catalog objects");

	}

	/**
	 * Refreshes reference and session overlays.
	 * If another refresh is in progress, update is skipped.
	 * @param location
	 */
	protected final void refreshSessionOverlays(final Location location) {
		if (!mRefreshSessionPending) {
			Log.d(TAG, "Updating session overlay");
			mRefreshSessionPending = true;
			proceedAfterSessionObjectsLoaded(null);
			sessionObjectsRefreshTime = System.currentTimeMillis();
			sessionObjectsRefreshedAt = location;
		} else {
			Log.v(TAG, "Session overlay refresh in progress. Skipping refresh..");
		}
	}

	/**
	 * Is new location far enough from last refresh location and is last refresh to old?
	 * @return true if session overlay needs refresh
	 */
	private boolean needSessionObjectsRefresh(final Location current) {
		if (current == null) { 
			// fail safe: draw if something went wrong
			return true;
		}

		return (
				(sessionObjectsRefreshedAt.distanceTo(current) > SESSION_REFRESH_DISTANCE)
				&& ((System.currentTimeMillis() - sessionObjectsRefreshTime) > SESSION_REFRESH_INTERVAL) 
				);
	}

	/**
	 * Loads session wifis in visible range.
	 * Will call onSessionLoaded callback upon completion
	 * @param highlight
	 * 			If highlight is specified only this wifi is displayed
	 */
	private void proceedAfterSessionObjectsLoaded(final WifiRecord highlight) {
		BoundingBox bbox = MapPositionUtil.getBoundingBox(
				mMapView.getModel().mapViewPosition.getMapPosition(),
				mMapView.getDimension());

		if (highlight == null) {
			// load all session wifis

			double minLatitude = bbox.minLatitude;
			double maxLatitude = bbox.maxLatitude;
			double minLongitude = bbox.minLongitude;
			double maxLongitude = bbox.maxLongitude;

			// query more than visible objects for smoother data scrolling / less database queries?
			if (PREFETCH_MAP_OBJECTS) {
				double latSpan = maxLatitude - minLatitude;
				double lonSpan = maxLongitude - minLongitude;
				minLatitude -= latSpan * 0.5;
				maxLatitude += latSpan * 0.5;
				minLongitude -= lonSpan * 0.5;
				maxLongitude += lonSpan * 0.5;
			}

			SessionMapObjectsLoader task = new SessionMapObjectsLoader(this);
			task.execute(sessionId, minLatitude, maxLatitude, minLongitude, maxLatitude, null);
		} else {
			// draw specific wifi
			SessionMapObjectsLoader task = new SessionMapObjectsLoader(this);
			task.execute(sessionId, bbox.minLatitude, bbox.maxLatitude, bbox.minLongitude, bbox.maxLatitude, highlight.getBssid());
		}
	}

	/* (non-Javadoc)
	 * @see org.openbmap.utils.SessionMapObjectsLoader.OnSessionLoadedListener#onSessionLoaded(java.util.ArrayList)
	 */
	@Override
	public final void onSessionLoaded(final ArrayList<LatLong> points) {
		Log.d(TAG, "Loaded session objects");
		Layers layers = this.mMapView.getLayerManager().getLayers();

		// clear overlay
		for (Iterator<Layer> iterator = sessionObjects.iterator(); iterator.hasNext();) {
			Layer layer = (Layer) iterator.next();
			layers.remove(layer);
		}
		sessionObjects.clear();

		for (int i = 0; i < points.size(); i++) {
			Circle circle = new Circle(points.get(i), CIRCLE_SESSION_WIDTH, paintSessionFill, null);
			sessionObjects.add(circle);
		}		

		int insertAfter = -1;
		if (layers.size() > 0 && catalogObjects.size() > 0) {
			// map + catalog objects
			insertAfter = layers.indexOf((Layer) catalogObjects.get(catalogObjects.size() - 1));
		} else if (layers.size() > 0 && catalogObjects.size() == 0) {
			// map + no catalog objects
			insertAfter = 1;
		} else {
			// no map + no catalog objects
			insertAfter = 0;
		}

		for (int i = 0; i < sessionObjects.size(); i++) {
			layers.add(insertAfter + i, sessionObjects.get(i));
		}		

		// if we have just loaded on point, set map center
		if (points.size() == 1) {
			mMapView.getModel().mapViewPosition.setCenter((LatLong) points.get(0));
		}

		// enable next refresh
		mRefreshSessionPending = false;
		Log.d(TAG, "Drawed catalog objects");
	}

	/**
	 * @param location
	 */
	private void refreshGpxTrace(final Location location) {
		if (!mRefreshGpxPending) {
			Log.d(TAG, "Updating gpx overlay");
			mRefreshGpxPending = true;
			proceedAfterGpxObjectsLoaded();
			gpxRefreshTime = System.currentTimeMillis();
		} else {
			Log.v(TAG, "Gpx overlay refresh in progress. Skipping refresh..");
		}
	}

	/**
	 * Is last gpx overlay update to old?
	 * @return true if overlay needs refresh
	 */
	private boolean needGpxRefresh() {
		return ((System.currentTimeMillis() - gpxRefreshTime) > GPX_REFRESH_INTERVAL);
	}

	/*
	 * Loads gpx points in visible range.
	 */
	private void proceedAfterGpxObjectsLoaded() {
		BoundingBox bbox = MapPositionUtil.getBoundingBox(
				mMapView.getModel().mapViewPosition.getMapPosition(),
				mMapView.getDimension());
		GpxMapObjectsLoader task = new GpxMapObjectsLoader(this);
		// query with some extra space
		task.execute(bbox.minLatitude - 0.01, bbox.maxLatitude + 0.01, bbox.minLongitude - 0.15, bbox.maxLatitude + 0.15);
		//task.execute(null, null, null, null);
	}

	/**
	 * Callback function for loadGpxObjects()
	 */
	@Override
	public final void onGpxLoaded(final ArrayList<LatLong> points) {
		Log.d(TAG, "Loaded gpx objects");
		// clear overlay
		gpxObjects.getLatLongs().clear();
		this.mMapView.getLayerManager().getLayers().remove(gpxObjects);

		for (int i = 0; i < points.size(); i++) {
			gpxObjects.getLatLongs().add(points.get(i));
		}	

		this.mMapView.getLayerManager().getLayers().add(gpxObjects);

		mRefreshGpxPending = false;
	}

	/**
	 * Highlights single wifi on map.
	 * @param id wifi id to highlight
	 */
	public final void loadSingleObject(final int id) {
		Log.d(TAG, "Adding selected wifi to overlay: " + id);

		WifiRecord wifi = dbHelper.loadWifiById(id);

		if (wifi != null) {
			proceedAfterSessionObjectsLoaded(wifi);
		}
	}

	/**
	 * Draws arrow in direction of travel. If bearing is unavailable a generic position symbol is used.
	 * If another refresh is taking place, update is skipped
	 */
	private void refreshCompass(final Location location) {
		// ensure that previous refresh has been finished
		if (mRefreshDirectionPending) {
			return;
		}
		mRefreshDirectionPending = true;

		// determine which drawable we currently 
		ImageView iv = (ImageView) findViewById(R.id.position_marker);
		Integer id = (Integer) iv.getTag() == null ? 0 : (Integer) iv.getTag();

		if (location.hasBearing()) {
			// determine which drawable we currently use
			drawCompass(iv, id, location.getBearing());
		} else {
			// refresh only if needed
			if (id != R.drawable.cross) {
				iv.setImageResource(R.drawable.cross);
			}

			//Log.i(TAG, "Can't draw direction marker: no bearing provided");
		}
		mRefreshDirectionPending = false;
	}

	/**
	 * Draws compass
	 * @param iv image view used for compass
	 * @param ressourceId resource id compass needle
	 * @param bearing	bearing (azimuth)
	 */
	private void drawCompass(final ImageView iv, final Integer ressourceId, final float bearing) {
		// refresh only if needed
		if (ressourceId != R.drawable.arrow) {
			iv.setImageResource(R.drawable.arrow);
		}

		// rotate arrow
		Matrix matrix = new Matrix();
		iv.setScaleType(ScaleType.MATRIX);   //required
		matrix.postRotate(bearing, iv.getWidth() / 2f, iv.getHeight() / 2f);
		iv.setImageMatrix(matrix);
	}

	/**
	 * Opens selected map file
	 * @return a map file
	 */
	protected final File getMapFile() {
		File mapFile = new File(
				Environment.getExternalStorageDirectory().getPath()
				+ prefs.getString(Preferences.KEY_DATA_DIR, Preferences.VAL_DATA_DIR)
				+ Preferences.MAPS_SUBDIR, 
				prefs.getString(Preferences.KEY_MAP_FILE, Preferences.VAL_MAP_FILE));

		/*
		if (mapFile.exists()) {
			mMapView.setClickable(true);
			//mMapView.setBuiltInZoomControls(true);
			//mMapView.setMapFile(mapFile);
		} else {
			Log.i(TAG, "No map file found!");
			Toast.makeText(this.getBaseContext(), R.string.please_select_map, Toast.LENGTH_LONG).show();
			mMapView.setClickable(false);
			//mMapView.setBuiltInZoomControls(false);
			mMapView.setVisibility(View.GONE);
		}
		 */	
		return mapFile;
	}

	protected final void addLayers(final LayerManager layerManager, final TileCache tileCache, final MapViewPosition mapViewPosition) {
		layerManager.getLayers().add(MapUtils.createTileRendererLayer(tileCache, mapViewPosition, getMapFile()));
	}

	protected final TileCache createTileCache() {
		return MapUtils.createExternalStorageTileCache(this, getPersistableId());
	}

	/**
	 * @return the id that is used to save this mapview
	 */
	protected final String getPersistableId() {
		return this.getClass().getSimpleName();
	}

	/**
	 * Sets map-related object to null to enable garbage collection.
	 */
	private void releaseMap() {
		Log.i(TAG, "Releasing map components");
		// save map settings
		mMapView.getModel().save(this.preferencesFacade);
		this.preferencesFacade.save();

		if (mMapView != null) {
			mMapView.destroy();
		}
	}

	/**
	 * Creates gpx overlay line
	 * @param points
	 * @return
	 */
	/*
	private static Polyline createGpxSymbol(final ArrayList<LatLong> points) {
		Paint paintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
		paintStroke.setStyle(Paint.Style.STROKE);
		paintStroke.setColor(Color.BLACK);
		paintStroke.setAlpha(255);
		paintStroke.setStrokeWidth(3);
		paintStroke.setStrokeCap(Cap.ROUND);
		paintStroke.setStrokeJoin(Paint.Join.ROUND);

		Polygon chain = new Polygon(points);
		Polyline line = new Polyline(chain, paintStroke);
		return line;
	}
	 */

	static Paint createPaint(final int color, final int strokeWidth, final Style style) {
		Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
		paint.setColor(color);
		paint.setStrokeWidth(strokeWidth);
		paint.setStyle(style);

		return paint;
	}

}

