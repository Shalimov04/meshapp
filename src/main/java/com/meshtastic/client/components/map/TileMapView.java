package com.meshtastic.client.components.map;

import com.meshtastic.client.MeshApp;
import com.meshtastic.client.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.QuadCurve;
import javafx.scene.shape.Shape;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * JavaFX OSM map component with online tiles, a local cache, an external
 * offline tile directory, and custom overlays.
 * <p>
 * The component owns tile loading, zooming, panning, night mode, node markers,
 * distance measurement, area selection, and trace visualization.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class TileMapView extends StackPane {

    /** Size of one OSM tile, in pixels. */
    public static final int TILE_SIZE = 256;
    /** Minimum supported map zoom level. */
    public static final int MIN_ZOOM = 1;
    /** Maximum supported map zoom level. */
    public static final int MAX_ZOOM = 19;

    private static final double MAX_LATITUDE = 85.05112878;
    private static final int MEMORY_CACHE_LIMIT = 384;
    private static final double MARKER_SIZE = 32;
    private static final double MARKER_LABEL_SIZE = 28;
    private static final double MARKER_MAX_FONT_SIZE = 13;
    private static final double MARKER_MIN_FONT_SIZE = 7;
    private static final String TRACE_FORWARD_COLOR = "#58a6ff";
    private static final String TRACE_REVERSE_COLOR = "#f0883e";
    private static final String NIGHT_MODE_CLASS = "map-night-mode";
    static final String PROJECT_URL = "https://github.com/smikme/meshapp";
    static final String CONTACT_EMAIL = "ks@privatepractice.app";
    private static final String[] TILE_EXTENSIONS = {".png", ".jpg", ".jpeg"};
    private static final Path CACHE_BASE = Paths.get(
            System.getProperty("user.home", "."),
            ".meshapp",
            "map-tiles"
    );

    private final Pane tileLayer = new Pane();
    private final Pane areaLayer = new Pane();
    private final Pane traceLayer = new Pane();
    private final Pane markerLayer = new Pane();
    private final Pane traceLabelLayer = new Pane();
    private final Pane measureLayer = new Pane();
    private final Label attributionLabel = new Label();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_2)
            .build();
    private final ExecutorService tileExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "meshapp-tile-loader");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<TileKey, Image> memoryCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MEMORY_CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TileKey, Image> eldest) {
                    return size() > MEMORY_CACHE_LIMIT;
                }
            }
    );
    private final Map<TileKey, Image> nightMemoryCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MEMORY_CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TileKey, Image> eldest) {
                    return size() > MEMORY_CACHE_LIMIT;
                }
            }
    );
    private final Set<TileKey> inFlightDownloads = ConcurrentHashMap.newKeySet();
    private volatile Set<TileKey> visibleTiles = Set.of();
    private final List<GeoPoint> measurePoints = new ArrayList<>();

    private List<MapMarker> markers = List.of();
    private List<TraceSegment> traceSegments = List.of();
    private String selectedTraceKey;
    private ContextMenu traceOverflowMenu;
    private TileSource tileSource = TileSource.configured(System.getProperties());
    private Path externalTileRoot;
    private GeoBounds selectedArea;
    private boolean offlineOnly;
    private boolean nightMode;
    private boolean measuring;
    private boolean areaSelectionMode;
    private boolean areaSelectionActive;
    private double centerLatitude = 20;
    private double centerLongitude = 0;
    private int zoom = 2;
    private double pressX;
    private double pressY;
    private double dragStartCenterX;
    private double dragStartCenterY;
    private double areaStartX;
    private double areaStartY;
    private double areaEndX;
    private double areaEndY;
    private boolean dragged;
    private Consumer<String> statusListener = text -> {};
    private Consumer<GeoPoint> pointerListener = point -> {};
    private Consumer<String> measureListener = text -> {};
    private Consumer<String> areaSelectionListener = text -> {};
    private Consumer<MapMarker> markerClickListener = marker -> {};

    /**
     * Creates the map and wires mouse handlers for panning, zooming,
     * measurements, and rectangular area selection.
     */
    public TileMapView() {
        getStyleClass().add("tile-map-view");
        setMinSize(0, 0);
        setFocusTraversable(true);
        setCursor(Cursor.OPEN_HAND);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        tileLayer.getStyleClass().add("map-tile-layer");
        tileLayer.setMouseTransparent(true);
        areaLayer.getStyleClass().add("map-area-layer");
        areaLayer.setMouseTransparent(true);
        traceLayer.getStyleClass().add("map-trace-layer");
        traceLayer.setPickOnBounds(false);
        markerLayer.getStyleClass().add("map-marker-layer");
        markerLayer.setPickOnBounds(false);
        traceLabelLayer.getStyleClass().add("map-trace-label-layer");
        traceLabelLayer.setPickOnBounds(false);
        measureLayer.getStyleClass().add("map-measure-layer");
        measureLayer.setMouseTransparent(true);
        attributionLabel.getStyleClass().add("map-attribution-label");
        attributionLabel.setMouseTransparent(true);
        attributionLabel.setText(tileSource.attribution());

        getChildren().addAll(tileLayer, areaLayer, traceLayer, markerLayer, traceLabelLayer, measureLayer, attributionLabel);
        StackPane.setAlignment(attributionLabel, Pos.BOTTOM_LEFT);

        widthProperty().addListener((obs, oldValue, newValue) -> render());
        heightProperty().addListener((obs, oldValue, newValue) -> render());

        setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            requestFocus();
            pressX = event.getX();
            pressY = event.getY();
            dragged = false;
            if (areaSelectionMode) {
                areaSelectionActive = true;
                areaStartX = event.getX();
                areaStartY = event.getY();
                areaEndX = areaStartX;
                areaEndY = areaStartY;
                updateAreaOverlay();
                notifyAreaSelection();
                setCursor(Cursor.CROSSHAIR);
                event.consume();
                return;
            }
            dragStartCenterX = lonToPixelX(centerLongitude, zoom);
            dragStartCenterY = latToPixelY(centerLatitude, zoom);
            setCursor(measuring ? Cursor.CROSSHAIR : Cursor.CLOSED_HAND);
        });

        setOnMouseDragged(event -> {
            if (!event.isPrimaryButtonDown()) {
                return;
            }
            if (areaSelectionMode && areaSelectionActive) {
                areaEndX = event.getX();
                areaEndY = event.getY();
                dragged = Math.abs(areaEndX - areaStartX) > 3 || Math.abs(areaEndY - areaStartY) > 3;
                updateAreaOverlay();
                notifyAreaSelection();
                event.consume();
                return;
            }
            double dx = event.getX() - pressX;
            double dy = event.getY() - pressY;
            if (Math.abs(dx) > 3 || Math.abs(dy) > 3) {
                dragged = true;
            }
            double centerX = dragStartCenterX - dx;
            double centerY = dragStartCenterY - dy;
            centerLongitude = pixelXToLon(centerX, zoom);
            centerLatitude = pixelYToLat(centerY, zoom);
            normalizeCenter();
            render();
        });

        setOnMouseReleased(event -> {
            if (areaSelectionMode && areaSelectionActive) {
                areaSelectionActive = false;
                areaEndX = event.getX();
                areaEndY = event.getY();
                finishAreaSelection();
                setCursor(Cursor.CROSSHAIR);
                event.consume();
                return;
            }
            setCursor(measuring ? Cursor.CROSSHAIR : Cursor.OPEN_HAND);
        });

        setOnMouseClicked(event -> {
            if (areaSelectionMode) {
                event.consume();
                return;
            }
            if (measuring && !dragged && event.getButton() == MouseButton.PRIMARY) {
                GeoPoint point = screenToGeo(event.getX(), event.getY());
                measurePoints.add(point);
                updateMeasureOverlay();
                notifyMeasure();
                event.consume();
            }
        });

        setOnMouseMoved(event -> pointerListener.accept(screenToGeo(event.getX(), event.getY())));
        addEventHandler(ScrollEvent.SCROLL, this::handleScrollZoom);
    }

    /**
     * Sets the listener that receives the compact map status: zoom, tile count,
     * online/offline mode, and night mode.
     *
     * @param statusListener receives status text
     */
    public void setStatusListener(Consumer<String> statusListener) {
        this.statusListener = statusListener != null ? statusListener : text -> {};
        notifyStatus();
    }

    /**
     * Sets the listener that receives the geographic point under the mouse pointer.
     *
     * @param pointerListener receives geographic points
     */
    public void setPointerListener(Consumer<GeoPoint> pointerListener) {
        this.pointerListener = pointerListener != null ? pointerListener : point -> {};
    }

    /**
     * Sets the listener that receives the distance-measurement status text.
     *
     * @param measureListener receives measurement text
     */
    public void setMeasureListener(Consumer<String> measureListener) {
        this.measureListener = measureListener != null ? measureListener : text -> {};
        notifyMeasure();
    }

    /**
     * Sets the listener that receives the selected-area status text.
     *
     * @param areaSelectionListener receives area-selection text
     */
    public void setAreaSelectionListener(Consumer<String> areaSelectionListener) {
        this.areaSelectionListener = areaSelectionListener != null ? areaSelectionListener : text -> {};
        notifyAreaSelection();
    }

    /**
     * Sets the handler invoked when a node marker is clicked.
     *
     * @param markerClickListener receives the selected marker
     */
    public void setMarkerClickListener(Consumer<MapMarker> markerClickListener) {
        this.markerClickListener = markerClickListener != null ? markerClickListener : marker -> {};
    }

    /**
     * Centers the map at the given point and applies the requested zoom level.
     *
     * @param latitude  center latitude
     * @param longitude center longitude
     * @param zoom      OSM zoom level
     */
    public void setView(double latitude, double longitude, int zoom) {
        this.centerLatitude = clampLatitude(latitude);
        this.centerLongitude = normalizeLongitude(longitude);
        this.zoom = clampZoom(zoom);
        render();
    }

    /** @return latitude of the current map center */
    public double getCenterLatitude() {
        return centerLatitude;
    }

    /** @return longitude of the current map center */
    public double getCenterLongitude() {
        return centerLongitude;
    }

    /** @return current OSM zoom level */
    public int getZoom() {
        return zoom;
    }

    /** Zooms in around the center of the visible area. */
    public void zoomIn() {
        zoomAround(getWidth() / 2.0, getHeight() / 2.0, zoom + 1);
    }

    /** Zooms out around the center of the visible area. */
    public void zoomOut() {
        zoomAround(getWidth() / 2.0, getHeight() / 2.0, zoom - 1);
    }

    /**
     * Enables or disables the mode that uses only local tiles.
     *
     * @param offlineOnly {@code true} to avoid network requests to OSM
     */
    public void setOfflineOnly(boolean offlineOnly) {
        this.offlineOnly = offlineOnly;
        render();
    }

    /** @return {@code true} when the map is using local tiles only */
    public boolean isOfflineOnly() {
        return offlineOnly;
    }

    /**
     * Enables map night mode. Night mode transforms tiles only; markers,
     * traces, the ruler, and the selected area are left unchanged.
     *
     * @param nightMode {@code true} to render tiles with the night palette
     */
    public void setNightMode(boolean nightMode) {
        this.nightMode = nightMode;
        if (nightMode) {
            if (!getStyleClass().contains(NIGHT_MODE_CLASS)) {
                getStyleClass().add(NIGHT_MODE_CLASS);
            }
        } else {
            getStyleClass().remove(NIGHT_MODE_CLASS);
        }
        render();
    }

    /** @return {@code true} when tile night mode is enabled */
    public boolean isNightMode() {
        return nightMode;
    }

    /**
     * Sets the external offline tile directory in {@code z/x/y.png|jpg|jpeg} format.
     *
     * @param externalTileRoot tile root directory, or {@code null}
     */
    public void setExternalTileRoot(Path externalTileRoot) {
        this.externalTileRoot = externalTileRoot;
        memoryCache.clear();
        nightMemoryCache.clear();
        render();
    }

    /** @return current external tile directory, or {@code null} */
    public Path getExternalTileRoot() {
        return externalTileRoot;
    }

    /**
     * Enables or disables distance-measurement mode.
     * Enabling it turns off area-selection mode.
     *
     * @param measuring {@code true} to add ruler points with map clicks
     */
    public void setMeasuring(boolean measuring) {
        this.measuring = measuring;
        if (measuring) {
            areaSelectionMode = false;
            areaSelectionActive = false;
        }
        setCursor(measuring || areaSelectionMode ? Cursor.CROSSHAIR : Cursor.OPEN_HAND);
        notifyMeasure();
        updateAreaOverlay();
    }

    /** @return {@code true} when the ruler is active */
    public boolean isMeasuring() {
        return measuring;
    }

    /** Clears all ruler points and refreshes the measurement overlay. */
    public void clearMeasure() {
        measurePoints.clear();
        updateMeasureOverlay();
        notifyMeasure();
    }

    /**
     * Enables or disables rectangular area-selection mode.
     * Enabling it turns off the ruler.
     *
     * @param areaSelectionMode {@code true} to select an area by dragging
     */
    public void setAreaSelectionMode(boolean areaSelectionMode) {
        this.areaSelectionMode = areaSelectionMode;
        if (areaSelectionMode) {
            measuring = false;
        } else {
            areaSelectionActive = false;
        }
        setCursor(areaSelectionMode ? Cursor.CROSSHAIR : Cursor.OPEN_HAND);
        updateAreaOverlay();
        notifyAreaSelection();
    }

    /** @return {@code true} when area-selection mode is active */
    public boolean isAreaSelectionMode() {
        return areaSelectionMode;
    }

    /** Clears the selected area and its visual overlay. */
    public void clearSelectedArea() {
        selectedArea = null;
        areaSelectionActive = false;
        updateAreaOverlay();
        notifyAreaSelection();
    }

    /**
     * Replaces the set of node markers displayed on the map.
     *
     * @param markers markers to display
     */
    public void setMarkers(List<MapMarker> markers) {
        this.markers = markers != null ? List.copyOf(markers) : List.of();
        updateMarkerOverlay();
    }

    /**
     * Replaces the set of trace segments.
     *
     * @param traceSegments connection segments between nodes
     */
    public void setTraceSegments(List<TraceSegment> traceSegments) {
        this.traceSegments = traceSegments != null ? List.copyOf(traceSegments) : List.of();
        if (this.traceSegments.stream().noneMatch(segment -> traceKey(segment).equals(selectedTraceKey))) {
            selectedTraceKey = null;
        }
        if (traceOverflowMenu != null) {
            traceOverflowMenu.hide();
        }
        updateTraceOverlay();
    }

    /** Hides all traces from the map. */
    public void clearTraceSegments() {
        this.traceSegments = List.of();
        selectedTraceKey = null;
        if (traceOverflowMenu != null) {
            traceOverflowMenu.hide();
        }
        updateTraceOverlay();
    }

    /**
     * Chooses a center and zoom level that fit the selected traces on the map.
     *
     * @return {@code true} if there are coordinates to fit
     */
    public boolean fitTraceSegments() {
        if (traceSegments.isEmpty()) {
            return false;
        }

        List<GeoPoint> points = new ArrayList<>();
        for (TraceSegment segment : traceSegments) {
            if (isValidCoordinate(segment.from().latitude(), segment.from().longitude())) {
                points.add(segment.from());
            }
            if (isValidCoordinate(segment.to().latitude(), segment.to().longitude())) {
                points.add(segment.to());
            }
        }
        if (points.isEmpty()) {
            return false;
        }
        if (points.size() == 1) {
            GeoPoint point = points.getFirst();
            setView(point.latitude(), point.longitude(), Math.max(13, zoom));
            return true;
        }

        double availableWidth = Math.max(64, getWidth() - 112);
        double availableHeight = Math.max(64, getHeight() - 112);
        int bestZoom = MIN_ZOOM;
        double bestCenterX = 0;
        double bestCenterY = 0;

        for (int candidateZoom = MAX_ZOOM; candidateZoom >= MIN_ZOOM; candidateZoom--) {
            Bounds bounds = pointBounds(points, candidateZoom);
            if (bounds.width() <= availableWidth && bounds.height() <= availableHeight) {
                bestZoom = candidateZoom;
                bestCenterX = bounds.centerX();
                bestCenterY = bounds.centerY();
                break;
            }
            if (candidateZoom == MIN_ZOOM) {
                bestCenterX = bounds.centerX();
                bestCenterY = bounds.centerY();
            }
        }

        zoom = bestZoom;
        centerLongitude = pixelXToLon(bestCenterX, zoom);
        centerLatitude = pixelYToLat(bestCenterY, zoom);
        normalizeCenter();
        render();
        return true;
    }

    /**
     * Chooses a center and zoom level that fit all node markers on the map.
     *
     * @return {@code true} if there are markers to fit
     */
    public boolean fitMarkers() {
        if (markers.isEmpty()) {
            return false;
        }
        if (markers.size() == 1) {
            MapMarker marker = markers.getFirst();
            setView(marker.latitude(), marker.longitude(), Math.max(13, zoom));
            return true;
        }

        double availableWidth = Math.max(280, getWidth() - 96);
        double availableHeight = Math.max(220, getHeight() - 96);
        int bestZoom = MIN_ZOOM;
        double bestCenterX = 0;
        double bestCenterY = 0;

        for (int candidateZoom = MAX_ZOOM; candidateZoom >= MIN_ZOOM; candidateZoom--) {
            Bounds bounds = markerBounds(candidateZoom);
            if (bounds.width() <= availableWidth && bounds.height() <= availableHeight) {
                bestZoom = candidateZoom;
                bestCenterX = bounds.centerX();
                bestCenterY = bounds.centerY();
                break;
            }
            if (candidateZoom == MIN_ZOOM) {
                bestCenterX = bounds.centerX();
                bestCenterY = bounds.centerY();
            }
        }

        zoom = bestZoom;
        centerLongitude = pixelXToLon(bestCenterX, zoom);
        centerLatitude = pixelYToLat(bestCenterY, zoom);
        normalizeCenter();
        render();
        return true;
    }

    /** @return number of tiles visible in the current viewport */
    public int visibleTileCount() {
        return visibleTileKeys().size();
    }

    /**
     * Returns the built-in cache directory dedicated to the current source.
     *
     * @return source-specific local tile cache path
     */
    public Path cacheRoot() {
        return CACHE_BASE.resolve(tileSource.id());
    }

    /**
     * Returns the source used for interactive network requests.
     *
     * @return current tile source
     */
    public TileSource getTileSource() {
        return tileSource;
    }

    /**
     * Changes the source used for interactive viewport requests. This component
     * intentionally exposes no bulk-download operation, regardless of source.
     * Existing external offline tiles remain connected and continue to take priority.
     *
     * @param tileSource new interactive tile source
     * @throws NullPointerException if {@code tileSource} is {@code null}
     */
    public void setTileSource(TileSource tileSource) {
        this.tileSource = java.util.Objects.requireNonNull(tileSource, "tileSource");
        attributionLabel.setText(tileSource.attribution());
        memoryCache.clear();
        nightMemoryCache.clear();
        render();
    }

    /**
     * Formats a distance in meters or kilometers for UI display.
     *
     * @param meters distance in meters
     * @return a string such as {@code 250 m} or {@code 1.25 km}
     */
    public static String formatDistance(double meters) {
        if (meters < 1000.0) {
            return I18n.t("map.distance.meters", String.format(I18n.locale(), "%.0f", meters));
        }
        return I18n.t("map.distance.kilometers", String.format(I18n.locale(), "%.2f", meters / 1000.0));
    }

    /**
     * Computes the distance between two coordinates with the haversine formula.
     *
     * @param a first point
     * @param b second point
     * @return distance in meters
     */
    public static double distanceMeters(GeoPoint a, GeoPoint b) {
        double radius = 6_371_000.0;
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());
        double sinLat = Math.sin(dLat / 2.0);
        double sinLon = Math.sin(dLon / 2.0);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 2.0 * radius * Math.atan2(Math.sqrt(h), Math.sqrt(1.0 - h));
    }

    /**
     * Handles mouse-wheel scrolling as zooming around the cursor position.
     */
    private void handleScrollZoom(ScrollEvent event) {
        if (event.getDeltaY() == 0) {
            return;
        }
        int targetZoom = zoom + (event.getDeltaY() > 0 ? 1 : -1);
        zoomAround(event.getX(), event.getY(), targetZoom);
        event.consume();
    }

    /**
     * Changes the zoom level while keeping the geographic point under the given
     * screen position beneath the cursor.
     */
    private void zoomAround(double screenX, double screenY, int targetZoom) {
        int newZoom = clampZoom(targetZoom);
        if (newZoom == zoom || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        GeoPoint anchor = screenToGeo(screenX, screenY);
        double anchorX = lonToPixelX(anchor.longitude(), newZoom);
        double anchorY = latToPixelY(anchor.latitude(), newZoom);
        double centerX = anchorX - screenX + getWidth() / 2.0;
        double centerY = anchorY - screenY + getHeight() / 2.0;

        zoom = newZoom;
        centerLongitude = pixelXToLon(centerX, zoom);
        centerLatitude = pixelYToLat(centerY, zoom);
        normalizeCenter();
        render();
    }

    /**
     * Redraws all map layers: tiles, markers, selection, traces, and ruler.
     */
    private void render() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            visibleTiles = Set.of();
            return;
        }

        tileLayer.getChildren().clear();
        visibleTiles = Set.copyOf(new HashSet<>(visibleTileKeys()));
        int tileCount = 1 << zoom;
        double centerX = lonToPixelX(centerLongitude, zoom);
        double centerY = latToPixelY(centerLatitude, zoom);
        double topLeftX = centerX - getWidth() / 2.0;
        double topLeftY = centerY - getHeight() / 2.0;
        int startX = (int) Math.floor(topLeftX / TILE_SIZE);
        int endX = (int) Math.floor((topLeftX + getWidth()) / TILE_SIZE);
        int startY = Math.max(0, (int) Math.floor(topLeftY / TILE_SIZE));
        int endY = Math.min(tileCount - 1, (int) Math.floor((topLeftY + getHeight()) / TILE_SIZE));

        for (int x = startX; x <= endX; x++) {
            int wrappedX = Math.floorMod(x, tileCount);
            for (int y = startY; y <= endY; y++) {
                TileKey key = new TileKey(zoom, wrappedX, y);
                Node tileNode = createTileNode(key);
                tileNode.setLayoutX(x * TILE_SIZE - topLeftX);
                tileNode.setLayoutY(y * TILE_SIZE - topLeftY);
                tileLayer.getChildren().add(tileNode);
            }
        }

        updateMarkerOverlay();
        updateAreaOverlay();
        updateTraceOverlay();
        updateMeasureOverlay();
        notifyStatus();
    }

    /**
     * Creates the visual node for a single tile. If the tile is not available
     * locally, a placeholder is shown and a background download starts in online mode.
     */
    private Node createTileNode(TileKey key) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("map-tile");
        tile.setMinSize(TILE_SIZE, TILE_SIZE);
        tile.setPrefSize(TILE_SIZE, TILE_SIZE);
        tile.setMaxSize(TILE_SIZE, TILE_SIZE);

        Image image = loadLocalTileImage(key);
        if (image != null) {
            ImageView imageView = new ImageView(displayTileImage(key, image));
            imageView.setFitWidth(TILE_SIZE);
            imageView.setFitHeight(TILE_SIZE);
            imageView.setSmooth(true);
            tile.getChildren().add(imageView);
            if (!offlineOnly && networkLoadNeeded(key)) {
                downloadTileIfNeeded(key);
            }
            return tile;
        }

        tile.getStyleClass().add("map-tile-missing");
        Rectangle bg = new Rectangle(TILE_SIZE, TILE_SIZE);
        bg.getStyleClass().add("map-tile-missing-bg");
        Label label = new Label(key.zoom() + "/" + key.x() + "/" + key.y());
        label.getStyleClass().add("map-tile-missing-label");
        tile.getChildren().addAll(bg, label);

        if (!offlineOnly) {
            downloadTileIfNeeded(key);
        }

        return tile;
    }

    /**
     * Loads a tile from memory, the external directory, or the built-in cache.
     *
     * @return tile image, or {@code null} if the tile was not found
     */
    private Image loadLocalTileImage(TileKey key) {
        Image cached = memoryCache.get(key);
        if (cached != null) {
            return cached;
        }

        Path path = findLocalTile(key);
        if (path == null) {
            return null;
        }

        Image image = new Image(path.toUri().toString(), TILE_SIZE, TILE_SIZE, false, true, false);
        if (image.isError()) {
            return null;
        }
        memoryCache.put(key, image);
        return image;
    }

    /**
     * Returns the tile image for the current visual mode.
     * Night mode uses a separate cache for transformed images.
     */
    private Image displayTileImage(TileKey key, Image image) {
        if (!nightMode) {
            return image;
        }

        Image cached = nightMemoryCache.get(key);
        if (cached != null) {
            return cached;
        }

        Image nightImage = createNightTileImage(image);
        nightMemoryCache.put(key, nightImage);
        return nightImage;
    }

    /**
     * Creates a night-mode tile by transforming the palette pixel by pixel.
     * This works the same way for online, cached, and external offline tiles.
     */
    private Image createNightTileImage(Image source) {
        PixelReader reader = source.getPixelReader();
        if (reader == null) {
            return source;
        }

        int width = Math.max(1, (int) Math.round(source.getWidth()));
        int height = Math.max(1, (int) Math.round(source.getHeight()));
        WritableImage target = new WritableImage(width, height);
        PixelWriter writer = target.getPixelWriter();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                writer.setArgb(x, y, nightTileArgb(reader.getArgb(x, y)));
            }
        }
        return target;
    }

    /**
     * Converts a tile pixel into the dark blue night palette, preserving road
     * and label detail through brightness inversion.
     */
    private int nightTileArgb(int argb) {
        int alpha = (argb >>> 24) & 0xff;
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;

        double luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
        double invertedDetail = 1.0 - luminance;
        double level = clamp(0.12 + invertedDetail * 0.72, 0.0, 1.0);

        int nightRed = colorByte(level * 0.58 + (255 - red) / 255.0 * 0.07);
        int nightGreen = colorByte(level * 0.76 + (255 - green) / 255.0 * 0.08);
        int nightBlue = colorByte(level + (255 - blue) / 255.0 * 0.08);
        return (alpha << 24) | (nightRed << 16) | (nightGreen << 8) | nightBlue;
    }

    /**
     * Converts a normalized color component {@code 0..1} into a {@code 0..255} byte value.
     */
    private int colorByte(double value) {
        return (int) Math.round(clamp(value, 0.0, 1.0) * 255.0);
    }

    /**
     * Schedules a background tile download if it is not already loading and is absent locally.
     */
    private void downloadTileIfNeeded(TileKey key) {
        if (!visibleTiles.contains(key) || inFlightDownloads.contains(key) || !networkLoadNeeded(key)) {
            return;
        }
        if (!inFlightDownloads.add(key)) {
            return;
        }

        tileExecutor.submit(() -> {
            try {
                if (!visibleTiles.contains(key)) {
                    return;
                }
                downloadTileFromNetwork(key);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Missing network/cache is represented by the placeholder tile.
            } finally {
                inFlightDownloads.remove(key);
                if (visibleTiles.contains(key)) {
                    Platform.runLater(this::render);
                }
            }
        });
    }

    /** Returns whether the visible tile is missing or its HTTP cache lifetime has expired. */
    private boolean networkLoadNeeded(TileKey key) {
        if (findTileInRoot(externalTileRoot, key) != null) {
            return false;
        }
        Path cached = cachePath(key);
        return !Files.isRegularFile(cached) || !TileCacheMetadata.isFresh(cached, Instant.now());
    }

    /**
     * Downloads one tile from the public OSM server and atomically stores it in the local cache.
     *
     * @return {@code true} if the tile is available locally after the method completes
     */
    private boolean downloadTileFromNetwork(TileKey key) throws IOException, InterruptedException {
        Path target = cachePath(key);
        boolean cached = Files.isRegularFile(target);
        if (cached && TileCacheMetadata.isFresh(target, Instant.now())) {
            return true;
        }

        Files.createDirectories(target.getParent());
        TileCacheMetadata previous = TileCacheMetadata.load(target).orElse(null);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(tileSource.tileUri(key.zoom(), key.x(), key.y()))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", userAgent())
                .GET();
        if (cached && previous != null) {
            previous.addValidators(requestBuilder);
        }
        HttpRequest request = requestBuilder.build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        Instant now = Instant.now();
        if (response.statusCode() == 304 && cached) {
            TileCacheMetadata.fromHeaders(response.headers(), now, previous).save(target);
            return true;
        }
        if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
            return false;
        }

        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, response.body());
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        TileCacheMetadata.fromHeaders(response.headers(), now, previous).save(target);
        memoryCache.remove(key);
        nightMemoryCache.remove(key);
        return true;
    }

    /**
     * Builds the User-Agent used for OSM requests, including the application version.
     */
    private String userAgent() {
        String version = MeshApp.APPLICATION_VERSION != null ? MeshApp.APPLICATION_VERSION : "dev";
        return userAgent(version);
    }

    /**
     * Builds the stable, contactable identity sent to raster tile providers.
     *
     * @param version application version
     * @return HTTP User-Agent value
     */
    static String userAgent(String version) {
        return "MeshApp/" + version + " (+" + PROJECT_URL + "; contact: " + CONTACT_EMAIL + ")";
    }

    /**
     * Looks for a tile first in the external directory, then in the built-in cache.
     */
    private Path findLocalTile(TileKey key) {
        Path external = findTileInRoot(externalTileRoot, key);
        if (external != null) {
            return external;
        }
        return findTileInRoot(cacheRoot(), key);
    }

    /**
     * Finds a tile file in a directory laid out as {@code z/x/y.png|jpg|jpeg}.
     */
    private Path findTileInRoot(Path root, TileKey key) {
        if (root == null) {
            return null;
        }
        Path dir = root.resolve(Integer.toString(key.zoom())).resolve(Integer.toString(key.x()));
        for (String ext : TILE_EXTENSIONS) {
            Path file = dir.resolve(key.y() + ext);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    /**
     * Returns the path where the tile should be stored in the built-in cache.
     */
    private Path cachePath(TileKey key) {
        return cacheRoot()
                .resolve(Integer.toString(key.zoom()))
                .resolve(Integer.toString(key.x()))
                .resolve(key.y() + ".png");
    }

    /**
     * Computes the tile keys intersecting the current map viewport.
     */
    private List<TileKey> visibleTileKeys() {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return List.of();
        }

        int tileCount = 1 << zoom;
        double centerX = lonToPixelX(centerLongitude, zoom);
        double centerY = latToPixelY(centerLatitude, zoom);
        double topLeftX = centerX - getWidth() / 2.0;
        double topLeftY = centerY - getHeight() / 2.0;
        int startX = (int) Math.floor(topLeftX / TILE_SIZE);
        int endX = (int) Math.floor((topLeftX + getWidth()) / TILE_SIZE);
        int startY = Math.max(0, (int) Math.floor(topLeftY / TILE_SIZE));
        int endY = Math.min(tileCount - 1, (int) Math.floor((topLeftY + getHeight()) / TILE_SIZE));

        List<TileKey> keys = new ArrayList<>();
        for (int x = startX; x <= endX; x++) {
            int wrappedX = Math.floorMod(x, tileCount);
            for (int y = startY; y <= endY; y++) {
                TileKey key = new TileKey(zoom, wrappedX, y);
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    /**
     * Redraws the marker layer, keeping only valid nodes near the viewport.
     */
    private void updateMarkerOverlay() {
        markerLayer.getChildren().clear();
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        for (MapMarker marker : markers) {
            if (!isValidCoordinate(marker.latitude(), marker.longitude())) {
                continue;
            }
            Point2D screen = geoToScreen(marker.latitude(), marker.longitude());
            if (screen.getX() < -64 || screen.getX() > getWidth() + 64
                    || screen.getY() < -64 || screen.getY() > getHeight() + 64) {
                continue;
            }
            StackPane markerNode = createMarkerNode(marker);
            markerNode.setLayoutX(screen.getX() - MARKER_SIZE / 2.0);
            markerNode.setLayoutY(screen.getY() - MARKER_SIZE / 2.0);
            markerLayer.getChildren().add(markerNode);
        }
        updateTraceMarkerFocus();
    }

    private Set<GeoPoint> selectedTracePoints() {
        Set<GeoPoint> points = new HashSet<>();
        for (TraceSegment segment : traceSegments) {
            if (traceKey(segment).equals(selectedTraceKey)) {
                points.add(segment.from());
                points.add(segment.to());
            }
        }
        return points;
    }

    /** Keep route nodes usable while unrelated marker text recedes from its path. */
    private void updateTraceMarkerFocus() {
        boolean focused = selectedTraceKey != null;
        Set<GeoPoint> points = selectedTracePoints();
        for (Node node : markerLayer.getChildren()) {
            if (node.getUserData() instanceof MapMarker marker) {
                boolean routeNode = points.contains(new GeoPoint(marker.latitude(), marker.longitude()));
                node.setOpacity(!focused || routeNode ? 1 : 0.14);
            }
        }
    }

    /**
     * Redraws the current selected area and the active rectangular selection.
     */
    private void updateAreaOverlay() {
        areaLayer.getChildren().clear();
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        if (selectedArea != null) {
            Rectangle selectedRect = rectangleForArea(selectedArea);
            selectedRect.getStyleClass().add("map-selected-area");
            areaLayer.getChildren().add(selectedRect);
        }

        if (areaSelectionActive) {
            Rectangle selectionRect = rectangleForScreenBounds(areaStartX, areaStartY, areaEndX, areaEndY);
            selectionRect.getStyleClass().add("map-area-selection");
            areaLayer.getChildren().add(selectionRect);
        }
    }

    /**
     * Draws attached routes first, then lays out labels above the marker layer.
     * Labels stay near their edge; details that cannot fit remain in an explicit
     * overflow menu rather than overlapping or disappearing without explanation.
     */
    private void updateTraceOverlay() {
        updateTraceOverlay(null);
    }

    private void updateTraceOverlay(TraceSegment preferredFocus) {
        TraceFocus focus = captureTraceFocus(preferredFocus);
        updateTraceMarkerFocus();
        traceLayer.getChildren().clear();
        traceLabelLayer.getChildren().clear();
        if (getWidth() <= 0 || getHeight() <= 0 || traceSegments.isEmpty()) {
            restoreTraceFocus(focus);
            return;
        }

        Map<EdgeKey, List<TraceSegment>> sharedEdges = new LinkedHashMap<>();
        for (TraceSegment segment : traceSegments) {
            if (isValidCoordinate(segment.from().latitude(), segment.from().longitude())
                    && isValidCoordinate(segment.to().latitude(), segment.to().longitude())) {
                sharedEdges.computeIfAbsent(edgeKey(segment), key -> new ArrayList<>()).add(segment);
            }
        }
        List<TraceVisual> visuals = new ArrayList<>();
        for (List<TraceSegment> shared : sharedEdges.values()) {
            // Persistent identity determines lane order, with no five-trace wraparound.
            shared.sort(Comparator.comparingLong(TraceSegment::traceId)
                    .thenComparingInt(TraceSegment::traceIndex).thenComparing(TraceSegment::reverse));
            for (int index = 0; index < shared.size(); index++) {
                TraceSegment segment = shared.get(index);
                Point2D from = geoToScreen(segment.from().latitude(), segment.from().longitude());
                Point2D to = geoToScreen(segment.to().latitude(), segment.to().longitude());
                double world = worldSize(zoom);
                double dx = to.getX() - from.getX();
                if (Math.abs(dx) > world / 2) {
                    // The nearest endpoint copy must follow the shortest geographic link.
                    to = to.add(dx > 0 ? -world : world, 0);
                }
                double spacing = shared.size() <= 1 ? 0 : Math.min(20, 240.0 / (shared.size() - 1));
                double lane = (index - (shared.size() - 1) / 2.0) * spacing;
                TraceGeometry geometry = traceGeometry(segment, from, to, lane, index);
                Shape path = geometry.shape();
                path.getStyleClass().add("map-trace-line");
                if (segment.reverse()) {
                    path.getStyleClass().add("map-trace-line-reverse");
                }
                if (segment.hiddenHopCount() > 0) {
                    path.getStyleClass().add("map-trace-line-gap");
                }
                boolean selected = traceKey(segment).equals(selectedTraceKey);
                String color = traceColor(segment.reverse());
                path.setStyle("-fx-stroke: " + color + "; -fx-fill: null;"
                        + (selected ? " -fx-stroke-width: 5; -fx-opacity: 1;"
                        : selectedTraceKey != null ? " -fx-opacity: 0.22;" : ""));
                path.setUserData(segment);
                path.setCursor(Cursor.HAND);
                path.setOnMouseClicked(event -> {
                    if (!measuring && !areaSelectionMode && !dragged && event.getButton() == MouseButton.PRIMARY) {
                        selectTrace(segment);
                        event.consume();
                    }
                });
                Tooltip.install(path, new Tooltip(traceDetails(segment)));
                traceLayer.getChildren().add(path);
                double arrowOpacity = selectedTraceKey == null || selected ? 0.92 : 0.22;
                addTraceArrow(geometry, 0.42, color, arrowOpacity);
                if (from.distance(to) > 180) {
                    addTraceArrow(geometry, 0.72, color, arrowOpacity);
                }
                visuals.add(new TraceVisual(segment, geometry));
            }
        }
        layoutTraceLabels(visuals);
        restoreTraceFocus(focus);
    }

    /** Capture only focus owned by this overlay; toolbar and other controls keep theirs. */
    private TraceFocus captureTraceFocus(TraceSegment preferredFocus) {
        Node owner = getScene() == null ? null : getScene().getFocusOwner();
        if (owner == null || owner.getParent() != traceLabelLayer) {
            return null;
        }
        if (owner.getStyleClass().contains("map-trace-overflow")) {
            return new TraceFocus(preferredFocus, true);
        }
        return owner.getUserData() instanceof TraceSegment segment ? new TraceFocus(segment, false) : null;
    }

    private void restoreTraceFocus(TraceFocus focus) {
        if (focus == null) {
            return;
        }
        // The replacement must be attached and sized before it can own keyboard focus.
        traceLabelLayer.layout();
        Node overflow = null;
        Node selected = null;
        Node first = null;
        for (Node node : traceLabelLayer.getChildren()) {
            if (node.getStyleClass().contains("map-trace-overflow")) {
                overflow = node;
            } else if (node.getUserData() instanceof TraceSegment segment) {
                if (focus.segment() != null && sameTraceLabel(segment, focus.segment())) {
                    node.requestFocus();
                    return;
                }
                if (traceKey(segment).equals(selectedTraceKey) && selected == null) {
                    selected = node;
                }
                if (first == null) {
                    first = node;
                }
            }
        }
        if (overflow != null) {
            overflow.requestFocus();
        } else if (focus.overflow() && selected != null) {
            selected.requestFocus();
        } else if (focus.overflow() && first != null) {
            first.requestFocus();
        } else {
            requestFocus();
        }
    }

    private boolean sameTraceLabel(TraceSegment first, TraceSegment second) {
        return traceKey(first).equals(traceKey(second)) && first.reverse() == second.reverse()
                && first.from().equals(second.from()) && first.to().equals(second.to());
    }

    private TraceGeometry traceGeometry(TraceSegment segment, Point2D from, Point2D to,
                                        double lane, int index) {
        Point2D delta = to.subtract(from);
        if (delta.magnitude() < 1) {
            // A local link is real data, even though its geographic length is zero.
            // The loop returns to the same node instead of silently losing a link.
            double radius = 28 + index * Math.min(9, 100.0 / (index + 1));
            double side = index % 2 == 0 ? 1 : -1;
            Point2D first = from.add(-radius * side, -radius * 1.8);
            Point2D second = from.add(radius * side, -radius * 1.8);
            if (segment.reverse()) {
                Point2D swap = first;
                first = second;
                second = swap;
            }
            CubicCurve loop = new CubicCurve(from.getX(), from.getY(), first.getX(), first.getY(),
                    second.getX(), second.getY(), to.getX(), to.getY());
            return new TraceGeometry(from, first, second, to, loop, true);
        }
        Point2D normal = new Point2D(-delta.getY(), delta.getX()).normalize();
        if (shouldFlipTraceNormal(segment)) {
            normal = normal.multiply(-1);
        }
        Point2D control = from.midpoint(to).add(normal.multiply(lane * 2));
        QuadCurve curve = new QuadCurve(from.getX(), from.getY(), control.getX(), control.getY(),
                to.getX(), to.getY());
        return new TraceGeometry(from, control, control, to, curve, false);
    }

    private void layoutTraceLabels(List<TraceVisual> visuals) {
        List<Rectangle2D> occupied = new ArrayList<>();
        Set<GeoPoint> focusedPoints = selectedTracePoints();
        for (MapMarker marker : markers) {
            if (selectedTraceKey != null
                    && !focusedPoints.contains(new GeoPoint(marker.latitude(), marker.longitude()))) {
                continue;
            }
            Point2D point = geoToScreen(marker.latitude(), marker.longitude());
            if (point.getX() >= -MARKER_SIZE && point.getX() <= getWidth() + MARKER_SIZE
                    && point.getY() >= -MARKER_SIZE && point.getY() <= getHeight() + MARKER_SIZE) {
                occupied.add(new Rectangle2D(point.getX() - MARKER_SIZE / 2 - 4,
                        point.getY() - MARKER_SIZE / 2 - 4, MARKER_SIZE + 8, MARKER_SIZE + 8));
            }
        }
        // Reserve a stable, uncluttered place for overflow before placing any labels.
        double overflowWidth = Math.min(180, Math.max(60, getWidth() - 16));
        Rectangle2D overflowArea = new Rectangle2D(Math.max(8, getWidth() - overflowWidth - 8),
                8, overflowWidth, 30);
        occupied.add(overflowArea);
        occupied.add(new Rectangle2D(0, Math.max(0, getHeight() - 25), 170, 25));
        visuals.sort(Comparator.comparing((TraceVisual visual) ->
                !traceKey(visual.segment()).equals(selectedTraceKey)));
        List<TraceSegment> overflow = new ArrayList<>();
        for (TraceVisual visual : visuals) {
            TraceSegment segment = visual.segment();
            // Full identity/time and signal remain available in every tooltip/menu.
            Label label = new Label(visuals.size() > 8 ? compactTraceText(segment) : fullTraceText(segment));
            label.getStyleClass().add("map-trace-signal-label");
            if (traceKey(segment).equals(selectedTraceKey)) {
                label.getStyleClass().add("map-trace-signal-label-selected");
            }
            label.setStyle("-fx-border-color: " + traceColor(segment.reverse()) + ";");
            label.setUserData(segment);
            traceLabelLayer.getChildren().add(label);
            label.applyCss();
            label.autosize();
            LabelPlacement placement = placeTraceLabel(label, visual.geometry(), occupied);
            if (placement == null && visuals.size() <= 8) {
                label.setText(compactTraceText(segment));
                label.autosize();
                placement = placeTraceLabel(label, visual.geometry(), occupied);
            }
            if (placement == null) {
                traceLabelLayer.getChildren().remove(label);
                overflow.add(segment);
                continue;
            }
            Rectangle2D bounds = placement.bounds();
            label.resizeRelocate(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight());
            occupied.add(new Rectangle2D(bounds.getMinX() - 3, bounds.getMinY() - 3,
                    bounds.getWidth() + 6, bounds.getHeight() + 6));
            Point2D anchor = placement.anchor();
            double endX = Math.max(bounds.getMinX(), Math.min(bounds.getMaxX(), anchor.getX()));
            double endY = Math.max(bounds.getMinY(), Math.min(bounds.getMaxY(), anchor.getY()));
            if (anchor.distance(endX, endY) > 5) {
                Line leader = new Line(anchor.getX(), anchor.getY(), endX, endY);
                leader.getStyleClass().add("map-trace-label-leader");
                leader.setStyle("-fx-stroke: " + traceColor(segment.reverse()) + ";");
                leader.setMouseTransparent(true);
                traceLabelLayer.getChildren().add(traceLabelLayer.getChildren().indexOf(label), leader);
            }
            configureTraceLabel(label, segment);
        }
        if (!overflow.isEmpty()) {
            Label more = new Label(I18n.t("map.trace.more", overflow.size()));
            more.getStyleClass().add("map-trace-overflow");
            more.setUserData(List.copyOf(overflow));
            more.setFocusTraversable(true);
            more.setCursor(Cursor.HAND);
            more.setMaxWidth(overflowWidth);
            traceLabelLayer.getChildren().add(more);
            more.applyCss();
            more.autosize();
            more.relocate(overflowArea.getMaxX() - more.getWidth(), overflowArea.getMinY());
            more.setTooltip(new Tooltip(I18n.t("map.trace.more", overflow.size())));
            Runnable openMenu = () -> showTraceOverflow(more, overflow);
            more.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && !measuring && !areaSelectionMode && !dragged) {
                    openMenu.run();
                    event.consume();
                }
            });
            more.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                    openMenu.run();
                    event.consume();
                }
            });
        }
    }

    /** A bounded search along the owning edge, with leader cues for displaced labels. */
    private LabelPlacement placeTraceLabel(Label label, TraceGeometry geometry, List<Rectangle2D> occupied) {
        double width = Math.ceil(label.prefWidth(-1));
        double height = Math.ceil(label.prefHeight(-1));
        double[] positions = {0.5, 0.32, 0.68, 0.18, 0.82};
        double[] distances = {16, -16, 38, -38, 64, -64, 94, -94, 128, -128};
        for (double distance : distances) {
            for (double position : positions) {
                Point2D anchor = geometry.point(position);
                Point2D tangent = geometry.tangent(position);
                Point2D normal = new Point2D(-tangent.getY(), tangent.getX()).normalize();
                Point2D center = anchor.add(normal.multiply(distance));
                Rectangle2D bounds = new Rectangle2D(center.getX() - width / 2,
                        center.getY() - height / 2, width, height);
                if (bounds.getMinX() >= 8 && bounds.getMinY() >= 8
                        && bounds.getMaxX() <= getWidth() - 8 && bounds.getMaxY() <= getHeight() - 8
                        && occupied.stream().noneMatch(rectangle -> rectangle.intersects(bounds))) {
                    return new LabelPlacement(bounds, anchor);
                }
            }
        }
        return null;
    }

    private void configureTraceLabel(Label label, TraceSegment segment) {
        label.setCursor(Cursor.HAND);
        label.setFocusTraversable(true);
        label.setAccessibleText(traceDetails(segment));
        label.setTooltip(new Tooltip(traceDetails(segment)));
        label.setOnMouseClicked(event -> {
            if (!measuring && !areaSelectionMode && !dragged && event.getButton() == MouseButton.PRIMARY) {
                label.requestFocus();
                selectTrace(segment);
                event.consume();
            }
        });
        label.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                selectTrace(segment);
                event.consume();
            }
        });
    }

    private void showTraceOverflow(Label owner, List<TraceSegment> overflow) {
        if (traceOverflowMenu != null) {
            traceOverflowMenu.hide();
        }
        owner.requestFocus();
        traceOverflowMenu = new ContextMenu();
        for (TraceSegment segment : overflow) {
            MenuItem item = new MenuItem(traceDetails(segment).replace('\n', ' '));
            item.setOnAction(event -> selectTrace(segment));
            traceOverflowMenu.getItems().add(item);
        }
        traceOverflowMenu.show(owner, javafx.geometry.Side.BOTTOM, 0, 4);
    }

    private void selectTrace(TraceSegment segment) {
        String key = traceKey(segment);
        selectedTraceKey = key.equals(selectedTraceKey) ? null : key;
        updateTraceOverlay(segment);
    }

    private String fullTraceText(TraceSegment segment) {
        return segment.traceLabel() + " · " + segment.signalText();
    }

    private String compactTraceText(TraceSegment segment) {
        String identity = segment.traceId() != 0 ? "#" + segment.traceId() : "#" + (segment.traceIndex() + 1);
        String signal = !Double.isFinite(segment.snr()) ? I18n.t("map.trace.snrUnavailable")
                : segment.averagedSnr()
                ? I18n.t("map.trace.compactMean", String.format(Locale.ROOT, "%.1f", segment.snr()), I18n.t("node.unit.db"))
                : "SNR " + String.format(Locale.ROOT, "%.1f", segment.snr()) + " " + I18n.t("node.unit.db");
        // A bridged gap and an average remain explicit even in compact labels.
        return identity + " " + (segment.reverse() ? "←" : "→") + " · " + signal
                + (segment.hiddenHopCount() > 0 ? " · " + I18n.t("map.trace.compactGap", segment.hiddenHopCount()) : "");
    }

    private String traceDetails(TraceSegment segment) {
        return segment.traceLabel() + " · " + segment.traceTitle() + "\n"
                + segment.fromTitle() + " → " + segment.toTitle() + "\n" + segment.signalText();
    }

    private static String traceKey(TraceSegment segment) {
        return segment.traceId() != 0 ? "id:" + segment.traceId() : "index:" + segment.traceIndex();
    }

    private EdgeKey edgeKey(TraceSegment segment) {
        return shouldFlipTraceNormal(segment) ? new EdgeKey(segment.to(), segment.from())
                : new EdgeKey(segment.from(), segment.to());
    }

    /** Stable orientation keeps reciprocal routes on distinct lanes. */
    private boolean shouldFlipTraceNormal(TraceSegment segment) {
        int latCompare = Double.compare(segment.from().latitude(), segment.to().latitude());
        if (latCompare != 0) {
            return latCompare > 0;
        }
        return Double.compare(segment.from().longitude(), segment.to().longitude()) > 0;
    }

    private void addTraceArrow(TraceGeometry geometry, double position, String color, double opacity) {
        Point2D point = geometry.point(position);
        Point2D tangent = geometry.tangent(position);
        Polygon arrow = new Polygon(-6, -5, 7, 0, -6, 5);
        arrow.getStyleClass().add("map-trace-arrow");
        arrow.setStyle("-fx-fill: " + color + "; -fx-opacity: " + opacity + ";");
        arrow.setLayoutX(point.getX());
        arrow.setLayoutY(point.getY());
        arrow.setRotate(Math.toDegrees(Math.atan2(tangent.getY(), tangent.getX())));
        arrow.setMouseTransparent(true);
        traceLayer.getChildren().add(arrow);
    }

    private String traceColor(boolean reverse) {
        return reverse ? TRACE_REVERSE_COLOR : TRACE_FORWARD_COLOR;
    }

    private record EdgeKey(GeoPoint first, GeoPoint second) {}
    private record TraceFocus(TraceSegment segment, boolean overflow) {}
    private record TraceVisual(TraceSegment segment, TraceGeometry geometry) {}
    private record LabelPlacement(Rectangle2D bounds, Point2D anchor) {}

    private record TraceGeometry(Point2D from, Point2D first, Point2D second, Point2D to,
                                 Shape shape, boolean cubic) {
        Point2D point(double t) {
            double u = 1 - t;
            return cubic ? from.multiply(u * u * u).add(first.multiply(3 * u * u * t))
                    .add(second.multiply(3 * u * t * t)).add(to.multiply(t * t * t))
                    : from.multiply(u * u).add(first.multiply(2 * u * t)).add(to.multiply(t * t));
        }
        Point2D tangent(double t) {
            double u = 1 - t;
            return cubic ? first.subtract(from).multiply(3 * u * u)
                    .add(second.subtract(first).multiply(6 * u * t))
                    .add(to.subtract(second).multiply(3 * t * t))
                    : first.subtract(from).multiply(2 * u).add(to.subtract(first).multiply(2 * t));
        }
    }

    /**
     * Builds a screen rectangle for the geographic bounds of the selected area.
     */
    private Rectangle rectangleForArea(GeoBounds area) {
        Point2D topLeft = geoToScreenUnwrapped(area.north(), area.west());
        Point2D bottomRight = geoToScreenUnwrapped(area.south(), area.east());
        return rectangleForScreenBounds(topLeft.getX(), topLeft.getY(), bottomRight.getX(), bottomRight.getY());
    }

    /**
     * Creates a JavaFX rectangle from two screen points, regardless of drag direction.
     */
    private Rectangle rectangleForScreenBounds(double x1, double y1, double x2, double y2) {
        double x = Math.min(x1, x2);
        double y = Math.min(y1, y2);
        double width = Math.abs(x2 - x1);
        double height = Math.abs(y2 - y1);
        Rectangle rect = new Rectangle(x, y, width, height);
        rect.setMouseTransparent(true);
        return rect;
    }

    /**
     * Completes area selection by storing geographic bounds and fitting the map to them.
     */
    private void finishAreaSelection() {
        double width = Math.abs(areaEndX - areaStartX);
        double height = Math.abs(areaEndY - areaStartY);
        if (width < 18 || height < 18) {
            updateAreaOverlay();
            notifyAreaSelection();
            return;
        }

        GeoPoint first = screenToGeo(areaStartX, areaStartY);
        GeoPoint second = screenToGeo(areaEndX, areaEndY);
        selectedArea = GeoBounds.from(first, second);
        fitArea(selectedArea);
        notifyAreaSelection();
    }

    /**
     * Chooses a center and zoom level that fit the selected area in the viewport.
     */
    private void fitArea(GeoBounds area) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        double availableWidth = Math.max(180, getWidth() - 80);
        double availableHeight = Math.max(140, getHeight() - 80);
        int bestZoom = MIN_ZOOM;
        double bestCenterX = 0;
        double bestCenterY = 0;

        for (int candidateZoom = MAX_ZOOM; candidateZoom >= MIN_ZOOM; candidateZoom--) {
            double westX = lonToPixelX(area.west(), candidateZoom);
            double eastX = lonToPixelX(area.east(), candidateZoom);
            double northY = latToPixelY(area.north(), candidateZoom);
            double southY = latToPixelY(area.south(), candidateZoom);
            double width = Math.abs(eastX - westX);
            double height = Math.abs(southY - northY);
            if (width <= availableWidth && height <= availableHeight) {
                bestZoom = candidateZoom;
                bestCenterX = Math.min(westX, eastX) + width / 2.0;
                bestCenterY = Math.min(northY, southY) + height / 2.0;
                break;
            }
            if (candidateZoom == MIN_ZOOM) {
                bestCenterX = Math.min(westX, eastX) + width / 2.0;
                bestCenterY = Math.min(northY, southY) + height / 2.0;
            }
        }

        zoom = bestZoom;
        centerLongitude = pixelXToLon(bestCenterX, zoom);
        centerLatitude = pixelYToLat(bestCenterY, zoom);
        normalizeCenter();
        render();
    }

    /**
     * Creates a circular node marker with adaptive text sizing and a coordinate tooltip.
     */
    private StackPane createMarkerNode(MapMarker marker) {
        String markerText = sanitizeMarkerText(marker.shortTitle());
        Label label = new Label(markerText);
        label.getStyleClass().add("map-node-marker-label");
        label.setAlignment(Pos.CENTER);
        label.setTextOverrun(OverrunStyle.CLIP);
        label.setMinSize(MARKER_LABEL_SIZE, MARKER_LABEL_SIZE);
        label.setPrefSize(MARKER_LABEL_SIZE, MARKER_LABEL_SIZE);
        label.setMaxSize(MARKER_LABEL_SIZE, MARKER_LABEL_SIZE);
        label.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, fitMarkerFontSize(markerText)));

        StackPane pin = new StackPane(label);
        pin.setUserData(marker);
        pin.getStyleClass().add("map-node-marker");
        if (marker.local()) {
            pin.getStyleClass().add("map-node-marker-local");
        }
        pin.setCursor(measuring || areaSelectionMode ? Cursor.CROSSHAIR : Cursor.HAND);
        pin.setMinSize(MARKER_SIZE, MARKER_SIZE);
        pin.setPrefSize(MARKER_SIZE, MARKER_SIZE);
        pin.setMaxSize(MARKER_SIZE, MARKER_SIZE);
        pin.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || measuring || areaSelectionMode || dragged) {
                return;
            }
            markerClickListener.accept(marker);
            event.consume();
        });

        String tooltipText = marker.title()
                + "\n" + formatCoordinate(marker.latitude(), marker.longitude());
        Tooltip.install(pin, new Tooltip(tooltipText));
        return pin;
    }

    /**
     * Prepares compact marker text: empty values become a dot, and long values are
     * limited to four Unicode characters.
     */
    private String sanitizeMarkerText(String text) {
        if (text == null || text.isBlank()) {
            return "•";
        }
        String trimmed = text.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= 4) {
            return trimmed;
        }
        StringBuilder result = new StringBuilder();
        trimmed.codePoints().limit(4).forEach(result::appendCodePoint);
        return result.toString();
    }

    /**
     * Finds the largest font size that lets the text fit inside the marker.
     */
    private double fitMarkerFontSize(String text) {
        if (text == null || text.isBlank()) {
            return MARKER_MAX_FONT_SIZE;
        }
        for (double size = MARKER_MAX_FONT_SIZE; size >= MARKER_MIN_FONT_SIZE; size -= 0.5) {
            Text probe = new Text(text);
            probe.setFont(Font.font(Font.getDefault().getFamily(), FontWeight.BOLD, size));
            if (probe.getLayoutBounds().getWidth() <= MARKER_LABEL_SIZE - 2
                    && probe.getLayoutBounds().getHeight() <= MARKER_LABEL_SIZE - 2) {
                return size;
            }
        }
        return MARKER_MIN_FONT_SIZE;
    }

    /**
     * Redraws the measurement line, clicked points, and distance labels between adjacent points.
     */
    private void updateMeasureOverlay() {
        measureLayer.getChildren().clear();
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        if (measurePoints.size() >= 2) {
            Polyline line = new Polyline();
            line.getStyleClass().add("map-measure-line");
            for (GeoPoint point : measurePoints) {
                Point2D screen = geoToScreenUnwrapped(point.latitude(), point.longitude());
                line.getPoints().addAll(screen.getX(), screen.getY());
            }
            measureLayer.getChildren().add(line);
        }

        for (int i = 0; i < measurePoints.size(); i++) {
            GeoPoint point = measurePoints.get(i);
            Point2D screen = geoToScreenUnwrapped(point.latitude(), point.longitude());
            Circle circle = new Circle(5);
            circle.getStyleClass().add("map-measure-point");
            circle.setCenterX(screen.getX());
            circle.setCenterY(screen.getY());
            measureLayer.getChildren().add(circle);

            if (i > 0) {
                GeoPoint previous = measurePoints.get(i - 1);
                Point2D previousScreen = geoToScreenUnwrapped(previous.latitude(), previous.longitude());
                double distance = distanceMeters(previous, point);
                Label segment = new Label(formatDistance(distance));
                segment.getStyleClass().add("map-measure-segment-label");
                segment.setLayoutX((previousScreen.getX() + screen.getX()) / 2.0 + 6);
                segment.setLayoutY((previousScreen.getY() + screen.getY()) / 2.0 + 6);
                measureLayer.getChildren().add(segment);
            }
        }
    }

    /**
     * Publishes the current map status text.
     */
    private void notifyStatus() {
        int tileCount = visibleTileCount();
        statusListener.accept(I18n.t("map.status.summary",
                zoom,
                tileCount,
                pluralUnit("map.status.tile", tileCount),
                I18n.t(offlineOnly ? "map.status.mode.offline" : "map.status.mode.onlineCache"),
                nightMode ? I18n.t("map.status.nightSuffix") : ""));
    }

    /**
     * Publishes the current ruler state and total route length.
     */
    private void notifyMeasure() {
        if (measurePoints.isEmpty()) {
            measureListener.accept(I18n.t(measuring ? "map.measure.clickFirst" : "map.measure.off"));
            return;
        }

        double total = 0;
        for (int i = 1; i < measurePoints.size(); i++) {
            total += distanceMeters(measurePoints.get(i - 1), measurePoints.get(i));
        }
        if (measurePoints.size() == 1) {
            measureListener.accept(I18n.t("map.measure.onePoint"));
        } else {
            measureListener.accept(I18n.t("map.measure.points",
                    measurePoints.size(),
                    pluralUnit("map.unit.point", measurePoints.size()),
                    formatDistance(total)));
        }
    }

    /**
     * Publishes the selection state and selected-area size.
     */
    private void notifyAreaSelection() {
        if (areaSelectionActive) {
            areaSelectionListener.accept(I18n.t("map.area.selecting"));
            return;
        }
        if (selectedArea == null) {
            areaSelectionListener.accept(I18n.t(areaSelectionMode ? "map.area.drag" : "map.area.none"));
            return;
        }

        double centerLat = (selectedArea.north() + selectedArea.south()) / 2.0;
        double centerLon = (selectedArea.west() + selectedArea.east()) / 2.0;
        double widthMeters = distanceMeters(
                new GeoPoint(centerLat, selectedArea.west()),
                new GeoPoint(centerLat, selectedArea.east())
        );
        double heightMeters = distanceMeters(
                new GeoPoint(selectedArea.north(), centerLon),
                new GeoPoint(selectedArea.south(), centerLon)
        );
        areaSelectionListener.accept(I18n.t("map.area.selected",
                formatDistance(widthMeters),
                formatDistance(heightMeters)));
    }

    /**
     * Converts screen coordinates inside the component to latitude and longitude.
     */
    private GeoPoint screenToGeo(double screenX, double screenY) {
        double centerX = lonToPixelX(centerLongitude, zoom);
        double centerY = latToPixelY(centerLatitude, zoom);
        double worldX = centerX + screenX - getWidth() / 2.0;
        double worldY = centerY + screenY - getHeight() / 2.0;
        return new GeoPoint(pixelYToLat(worldY, zoom), pixelXToLon(worldX, zoom));
    }

    /**
     * Converts geographic coordinates to a screen point inside the component.
     */
    private Point2D geoToScreen(double latitude, double longitude) {
        Point2D point = geoToScreenUnwrapped(latitude, longitude);
        double world = worldSize(zoom);
        double dx = point.getX() - getWidth() / 2.0;
        // Markers and traces use the world copy nearest to the view centre.
        return new Point2D(point.getX() - Math.floor(dx / world + 0.5) * world, point.getY());
    }

    /**
     * Converts geographic coordinates without wrapping, so areas and measurement
     * legs wider than half the world keep their continuous shape.
     */
    private Point2D geoToScreenUnwrapped(double latitude, double longitude) {
        double x = lonToPixelX(longitude, zoom) - lonToPixelX(centerLongitude, zoom) + getWidth() / 2.0;
        double y = latToPixelY(latitude, zoom) - latToPixelY(centerLatitude, zoom) + getHeight() / 2.0;
        return new Point2D(x, y);
    }

    /**
     * Computes the pixel bounds of all markers at the given zoom level.
     */
    private Bounds markerBounds(int candidateZoom) {
        List<GeoPoint> points = new ArrayList<>();
        for (MapMarker marker : markers) {
            points.add(new GeoPoint(marker.latitude(), marker.longitude()));
        }
        return pointBounds(points, candidateZoom);
    }

    /**
     * Computes the pixel bounds of an arbitrary set of geographic points.
     */
    private Bounds pointBounds(List<GeoPoint> points, int candidateZoom) {
        List<Double> xs = new ArrayList<>();
        double minY = Double.POSITIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (GeoPoint point : points) {
            xs.add(lonToPixelX(point.longitude(), candidateZoom));
            double y = latToPixelY(point.latitude(), candidateZoom);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        xs.sort(Double::compare);
        double world = worldSize(candidateZoom);
        // Cut the longitude circle at its largest empty arc. The remaining arc
        // is the smallest bounds, including routes that cross the antimeridian.
        double largestGap = -1;
        int first = 0;
        for (int index = 0; index < xs.size(); index++) {
            int next = (index + 1) % xs.size();
            double gap = xs.get(next) + (next == 0 ? world : 0) - xs.get(index);
            if (gap > largestGap) {
                largestGap = gap;
                first = next;
            }
        }
        double minX = xs.get(first);
        double maxX = minX + world - largestGap;
        return new Bounds(minX, minY, maxX, maxY);
    }

    /**
     * Normalizes the map center into the allowed latitude and longitude ranges.
     */
    private void normalizeCenter() {
        centerLatitude = clampLatitude(centerLatitude);
        centerLongitude = normalizeLongitude(centerLongitude);
    }

    /**
     * Validates coordinate ranges and treats {@code 0,0} as missing node coordinates.
     */
    private static boolean isValidCoordinate(double latitude, double longitude) {
        return (latitude != 0 || longitude != 0)
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180;
    }

    /**
     * Converts longitude to a global Web Mercator pixel X coordinate.
     */
    private static double lonToPixelX(double longitude, int zoom) {
        double worldSize = worldSize(zoom);
        return (normalizeLongitude(longitude) + 180.0) / 360.0 * worldSize;
    }

    /**
     * Converts latitude to a global Web Mercator pixel Y coordinate.
     */
    private static double latToPixelY(double latitude, int zoom) {
        double lat = Math.toRadians(clampLatitude(latitude));
        double worldSize = worldSize(zoom);
        double mercator = Math.log(Math.tan(Math.PI / 4.0 + lat / 2.0));
        return (0.5 - mercator / (2.0 * Math.PI)) * worldSize;
    }

    /**
     * Converts a global Web Mercator pixel X coordinate to longitude.
     */
    private static double pixelXToLon(double pixelX, int zoom) {
        double worldSize = worldSize(zoom);
        double x = pixelX % worldSize;
        if (x < 0) {
            x += worldSize;
        }
        return x / worldSize * 360.0 - 180.0;
    }

    /**
     * Converts a global Web Mercator pixel Y coordinate to latitude.
     */
    private static double pixelYToLat(double pixelY, int zoom) {
        double worldSize = worldSize(zoom);
        double y = Math.max(0, Math.min(worldSize, pixelY));
        double n = Math.PI - 2.0 * Math.PI * y / worldSize;
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /**
     * Returns the Web Mercator world size in pixels at the given zoom level.
     */
    private static double worldSize(int zoom) {
        return TILE_SIZE * (double) (1 << zoom);
    }

    /**
     * Clamps latitude to the range supported by Web Mercator.
     */
    private static double clampLatitude(double latitude) {
        if (Double.isNaN(latitude)) {
            return 0;
        }
        return Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, latitude));
    }

    /**
     * Normalizes longitude into the {@code -180..180} range.
     */
    private static double normalizeLongitude(double longitude) {
        if (Double.isNaN(longitude)) {
            return 0;
        }
        double normalized = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return normalized == -180.0 ? 180.0 : normalized;
    }

    /**
     * Clamps zoom to the supported OSM range.
     */
    private static int clampZoom(int zoom) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /**
     * Clamps a number to the given range.
     */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Formats coordinates for the map status line.
     *
     * @param latitude  latitude
     * @param longitude longitude
     * @return string with six fractional digits
     */
    public static String formatCoordinate(double latitude, double longitude) {
        return String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude);
    }

    private static String pluralUnit(String keyPrefix, long value) {
        return I18n.t(keyPrefix + "." + I18n.pluralCategory(value));
    }

    /**
     * Geographic point in WGS84 degrees.
     *
     * @param latitude  latitude
     * @param longitude longitude
     */
    public record GeoPoint(double latitude, double longitude) {
    }

    /**
     * One visual trace segment between two nodes with coordinates.
     *
     * @param from       segment start point
     * @param to         segment end point
     * @param fromTitle  start node name
     * @param toTitle    end node name
     * @param traceTitle target trace title
     * @param signalText signal, direction, and hop-count label
     * @param snr        numeric SNR, or {@link Double#NaN} when unavailable
     * @param reverse    {@code true} for the reverse direction
     * @param traceIndex selected trace index, used to spread lines apart
     * @param traceId persistent identity, or zero for a legacy caller
     * @param traceLabel persistent identity and saved time for display
     * @param hiddenHopCount intermediate hops without coordinates
     * @param averagedSnr true when SNR summarizes several measured hops
     */
    public record TraceSegment(
            GeoPoint from,
            GeoPoint to,
            String fromTitle,
            String toTitle,
            String traceTitle,
            String signalText,
            double snr,
            boolean reverse,
            int traceIndex,
            long traceId,
            String traceLabel,
            int hiddenHopCount,
            boolean averagedSnr
    ) {
        /** Compatibility constructor for callers without persistent trace metadata. */
        public TraceSegment(GeoPoint from, GeoPoint to, String fromTitle, String toTitle,
                            String traceTitle, String signalText, double snr, boolean reverse, int traceIndex) {
            this(from, to, fromTitle, toTitle, traceTitle, signalText, snr, reverse, traceIndex,
                    0, "#" + (traceIndex + 1), 0, false);
        }
    }

    /**
     * Geographic bounds of a rectangular area.
     */
    private record GeoBounds(double north, double south, double west, double east) {
        /**
         * Creates bounds from any two opposite corners of a rectangle.
         */
        static GeoBounds from(GeoPoint first, GeoPoint second) {
            double north = Math.max(first.latitude(), second.latitude());
            double south = Math.min(first.latitude(), second.latitude());
            double west = Math.min(first.longitude(), second.longitude());
            double east = Math.max(first.longitude(), second.longitude());
            return new GeoBounds(north, south, west, east);
        }
    }

    /**
     * OSM tile key in the {@code z/x/y} scheme.
     */
    private record TileKey(int zoom, int x, int y) {
        private TileKey {
            if (zoom < MIN_ZOOM || zoom > MAX_ZOOM) {
                throw new IllegalArgumentException("Unsupported zoom: " + zoom);
            }
        }
    }

    /**
     * Pixel bounds for a set of points at a specific zoom level.
     */
    private record Bounds(double minX, double minY, double maxX, double maxY) {
        /** @return bounds width in pixels */
        double width() {
            return maxX - minX;
        }

        /** @return bounds height in pixels */
        double height() {
            return maxY - minY;
        }

        /** @return X coordinate of the bounds center */
        double centerX() {
            return minX + width() / 2.0;
        }

        /** @return Y coordinate of the bounds center */
        double centerY() {
            return minY + height() / 2.0;
        }
    }
}
