package com.meshtastic.client.components.map;

import com.meshtastic.client.TraceMapTestSupport;
import com.meshtastic.client.themes.ThemeManager;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Line;
import javafx.scene.shape.QuadCurve;
import javafx.scene.shape.Shape;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TileMapViewTraceTest extends TraceMapTestSupport {
    private static final TileMapView.GeoPoint A = new TileMapView.GeoPoint(51, 12);
    private static final TileMapView.GeoPoint B = new TileMapView.GeoPoint(51.02, 12.04);
    private static final TileMapView.GeoPoint C = new TileMapView.GeoPoint(51.04, 12.08);

    @Test
    void ordinaryForwardAndReverseLabelsDoNotOverlapAtFitOrAfterZoomOut() {
        fx(() -> {
            TileMapView map = map(1280, 720);
            map.setTraceSegments(route(0));
            assertTrue(map.fitTraceSegments());
            layout(map);
            assertLabelsAccountedForAndReadable(map, 4);
            map.zoomOut();
            map.zoomOut();
            layout(map);
            assertLabelsAccountedForAndReadable(map, 4);
            return null;
        });
    }

    @Test
    void denseSmallViewportUsesReadableLabelsOrExplicitOverflow() {
        fx(() -> {
            TileMapView map = map(650, 400);
            List<TileMapView.TraceSegment> segments = new ArrayList<>();
            for (int index = 0; index < 8; index++) segments.addAll(route(index));
            map.setTraceSegments(segments);
            map.fitTraceSegments();
            layout(map);
            assertLabelsAccountedForAndReadable(map, segments.size());
            return null;
        });
    }

    @Test
    void sixTracesSharingEndpointsHaveDistinctVisibleGeometry() {
        fx(() -> {
            TileMapView map = map(1280, 720);
            List<TileMapView.TraceSegment> segments = new ArrayList<>();
            for (int index = 0; index < 6; index++) segments.add(segment(A, C, false, index));
            map.setTraceSegments(segments);
            map.fitTraceSegments();
            layout(map);
            List<Shape> paths = paths(map);
            assertEquals(6, paths.size());
            assertEquals(6, paths.stream().map(TileMapViewTraceTest::geometry).distinct().count(),
                    "The sixth route must not reuse the first route's lane");
            assertLabelsAccountedForAndReadable(map, 6);
            return null;
        });
    }

    @Test
    void parallelRoutesAndBothDirectionsStayAnchoredToNodeCoordinates() {
        fx(() -> {
            TileMapView map = map(1280, 720);
            List<TileMapView.TraceSegment> segments = List.of(segment(A, C, false, 0),
                    segment(C, A, true, 0), segment(A, C, false, 1));
            map.setTraceSegments(segments);
            map.fitTraceSegments();
            layout(map);
            List<Shape> paths = paths(map);
            assertEquals(segments.size(), paths.size());
            for (Shape path : paths) {
                assertInstanceOf(TileMapView.TraceSegment.class, path.getUserData());
                TileMapView.TraceSegment segment = (TileMapView.TraceSegment) path.getUserData();
                assertNear(screen(map, segment.from()), start(path));
                assertNear(screen(map, segment.to()), end(path));
            }
            return null;
        });
    }

    @Test
    void coLocatedNodesHaveVisibleLoopWithSignalAndDirection() {
        fx(() -> {
            TileMapView map = map(1280, 720);
            map.setTraceSegments(List.of(segment(A, A, false, 0)));
            map.fitTraceSegments();
            layout(map);
            List<Shape> paths = paths(map);
            assertEquals(1, paths.size(), "A real link with equal endpoint positions must still be represented");
            Shape loop = paths.getFirst();
            assertTrue(loop.getBoundsInParent().getWidth() > 12 || loop.getBoundsInParent().getHeight() > 12);
            assertNear(screen(map, A), start(loop));
            assertNear(screen(map, A), end(loop));
            assertEquals(1, nodes(map, "map-trace-signal-label").size());
            assertFalse(nodes(map, "map-trace-arrow").isEmpty());
            return null;
        });
    }

    @Test
    void antimeridianFitUsesShortArcAndKeepsTheLinkOnScreen() {
        fx(() -> {
            TileMapView map = map(1280, 720);
            TileMapView.GeoPoint east = new TileMapView.GeoPoint(10, 179.8);
            TileMapView.GeoPoint west = new TileMapView.GeoPoint(10, -179.8);
            map.setTraceSegments(List.of(segment(east, west, false, 0)));
            assertTrue(map.fitTraceSegments());
            layout(map);
            assertTrue(map.getZoom() >= 9, "A 0.4 degree route should not fit as an almost-global route");
            assertTrue(Math.abs(map.getCenterLongitude()) > 179);
            assertWithinMap(map, screen(map, east));
            assertWithinMap(map, screen(map, west));
            Shape path = paths(map).getFirst();
            assertNear(screen(map, east), start(path));
            assertNear(screen(map, west), end(path));
            assertTrue(path.getBoundsInParent().getWidth() < map.getWidth());
            return null;
        });
    }

    @Test
    void signalLabelsArePaintedAboveNodeMarkers() {
        fx(() -> {
            TileMapView map = map(1280, 720);
            map.setMarkers(List.of(new MapMarker("a", "Owner", "A", A.latitude(), A.longitude(), true),
                    new MapMarker("c", "Target", "C", C.latitude(), C.longitude(), false)));
            map.setTraceSegments(List.of(segment(A, C, false, 0)));
            map.fitTraceSegments();
            layout(map);
            Pane markers = field(map, "markerLayer");
            assertEquals(2, markers.getChildren().size());
            List<Node> labels = nodes(map, "map-trace-signal-label");
            assertEquals(1, labels.size());
            Node labelLayer = labels.getFirst();
            while (labelLayer.getParent() != map) labelLayer = labelLayer.getParent();
            assertTrue(map.getChildrenUnmodifiable().indexOf(labelLayer)
                    > map.getChildrenUnmodifiable().indexOf(markers),
                    "Markers must not paint over trace signal text");
            return null;
        });
    }

    @ParameterizedTest
    @EnumSource(value = KeyCode.class, names = {"ENTER", "SPACE"})
    void keyboardSelectionPreservesFocusSoTheSameSegmentCanBeToggledAgain(KeyCode key) {
        fx(() -> {
            TileMapView map = map(1280, 720);
            map.setTraceSegments(route(0));
            map.fitTraceSegments();
            layout(map);
            Scene scene = map.getScene();
            Node original = nodes(map, "map-trace-signal-label").getFirst();
            Object segment = original.getUserData();
            original.requestFocus();
            assertSame(original, scene.getFocusOwner());

            pressFocusedKey(scene, key);
            Node selected = assertFocusedTraceLabel(scene, segment);
            assertNotSame(original, selected, "The selection redraw creates a replacement label");
            assertTrue(selected.getStyleClass().contains("map-trace-signal-label-selected"));

            pressFocusedKey(scene, key);
            Node deselected = assertFocusedTraceLabel(scene, segment);
            assertFalse(deselected.getStyleClass().contains("map-trace-signal-label-selected"));
            return null;
        });
    }

    @Test
    void traceRefreshAndZoomDoNotStealFocusFromAnUnrelatedControl() {
        fx(() -> {
            TileMapView map = new TileMapView();
            map.setOfflineOnly(true);
            TextField search = new TextField();
            VBox host = new VBox(search, map);
            Scene scene = new Scene(host, 1280, 820);
            host.resize(1280, 820);
            host.applyCss();
            host.layout();
            map.resize(1280, 720);
            map.setTraceSegments(route(0));
            map.fitTraceSegments();
            search.requestFocus();
            assertSame(search, scene.getFocusOwner());
            map.setTraceSegments(route(1));
            assertSame(search, scene.getFocusOwner());
            map.zoomOut();
            assertSame(search, scene.getFocusOwner());
            return null;
        });
    }

    @Test
    void choosingAnOverflowRouteLeavesFocusOnAnAttachedTraceControl() {
        fx(() -> {
            TileMapView map = map(650, 400);
            Stage stage = new Stage();
            stage.setScene(map.getScene());
            try {
                stage.show();
                List<TileMapView.TraceSegment> segments = new ArrayList<>();
                for (int index = 0; index < 8; index++) segments.addAll(route(index));
                map.setTraceSegments(segments);
                map.fitTraceSegments();
                layout(map);
                Node overflow = nodes(map, "map-trace-overflow").getFirst();
                @SuppressWarnings("unchecked")
                TileMapView.TraceSegment chosen = ((List<TileMapView.TraceSegment>) overflow.getUserData()).getFirst();
                overflow.requestFocus();
                assertSame(overflow, map.getScene().getFocusOwner());
                pressFocusedKey(map.getScene(), KeyCode.ENTER);
                ContextMenu menu = field(map, "traceOverflowMenu");
                assertNotNull(menu);
                assertTrue(menu.isShowing(), "Enter on overflow must open the route choices");
                menu.getItems().getFirst().fire();
                menu.hide();

                Node focused = map.getScene().getFocusOwner();
                assertNotNull(focused);
                assertSame(map.getScene(), focused.getScene(), "Focus must belong to a surviving control");
                assertTrue(focused.getStyleClass().contains("map-trace-signal-label")
                        || focused.getStyleClass().contains("map-trace-overflow"));
                if (focused.getUserData() instanceof TileMapView.TraceSegment segment) {
                    assertEquals(chosen.traceIndex(), segment.traceIndex());
                    assertTrue(focused.getStyleClass().contains("map-trace-signal-label-selected"));
                }
            } finally {
                stage.close();
            }
            return null;
        });
    }

    @Test
    void selectingTraceDimsUnrelatedMarkersWhileKeepingItsEndpointControlsAvailable() {
        fx(() -> {
            TileMapView map = map(1280, 720);
            map.setMarkers(List.of(new MapMarker("a", "Owner", "A", A.latitude(), A.longitude(), true),
                    new MapMarker("c", "Target", "C", C.latitude(), C.longitude(), false),
                    new MapMarker("b", "Unrelated", "B", B.latitude(), B.longitude(), false)));
            map.setTraceSegments(List.of(segment(A, C, false, 0)));
            map.fitTraceSegments();
            layout(map);
            Pane markerLayer = field(map, "markerLayer");
            assertEquals(3, markerLayer.getChildren().size());
            Node unrelated = markerLayer.getChildren().get(2);
            assertTrue(paths(map).getFirst().getBoundsInParent().intersects(unrelated.getBoundsInParent()),
                    "The unrelated marker must cover part of the route in this fixture");
            assertEquals(1, unrelated.getOpacity());
            nodes(map, "map-trace-signal-label").getFirst().requestFocus();
            pressFocusedKey(map.getScene(), KeyCode.ENTER);

            assertTrue(markerLayer.getChildren().get(2).getOpacity() < 0.3,
                    "Selecting the route must reveal it through the unrelated marker");
            for (Node endpoint : markerLayer.getChildren().subList(0, 2)) {
                assertEquals(1, endpoint.getOpacity());
                assertFalse(endpoint.isMouseTransparent());
                assertFalse(endpoint.isDisabled());
                assertNotNull(endpoint.getOnMouseClicked());
            }
            pressFocusedKey(map.getScene(), KeyCode.ENTER);
            for (Node marker : markerLayer.getChildren()) assertEquals(1, marker.getOpacity());
            return null;
        });
    }

    private static void pressFocusedKey(Scene scene, KeyCode key) {
        Node target = scene.getFocusOwner();
        assertNotNull(target, "Keyboard input needs a focus owner");
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key,
                false, false, false, false));
    }

    private static Node assertFocusedTraceLabel(Scene scene, Object segment) {
        Node focused = scene.getFocusOwner();
        assertNotNull(focused, "Redrawing trace labels must retain keyboard focus");
        assertSame(scene, focused.getScene(), "The focus owner must be attached to the scene");
        assertTrue(focused.getStyleClass().contains("map-trace-signal-label"));
        assertEquals(segment, focused.getUserData(), "Focus must follow the same logical segment");
        return focused;
    }

    private static TileMapView map(double width, double height) {
        TileMapView map = new TileMapView();
        map.setOfflineOnly(true);
        Scene scene = new Scene(map, width, height);
        ThemeManager.applyTheme(scene, false);
        map.resize(width, height);
        layout(map);
        return map;
    }

    private static void layout(TileMapView map) {
        map.applyCss();
        map.layout();
    }

    private static List<TileMapView.TraceSegment> route(int index) {
        return List.of(segment(A, B, false, index), segment(B, C, false, index),
                segment(C, B, true, index), segment(B, A, true, index));
    }

    private static TileMapView.TraceSegment segment(TileMapView.GeoPoint from, TileMapView.GeoPoint to,
                                                   boolean reverse, int index) {
        return new TileMapView.TraceSegment(from, to, "Node A", "Node C", "Target " + index,
                (reverse ? "Reverse" : "Forward") + " · 2 hops · SNR 5.0 dB", 5, reverse, index);
    }

    private static void assertLabelsAccountedForAndReadable(TileMapView map, int count) {
        List<Node> labels = nodes(map, "map-trace-signal-label");
        List<Node> overflow = nodes(map, "map-trace-overflow");
        int hidden = overflow.stream().map(Label.class::cast)
                .mapToInt(label -> Integer.parseInt(label.getText().replaceAll("[^0-9]", ""))).sum();
        assertEquals(count, labels.size() + hidden,
                "Every segment needs a signal label or a counted, inspectable overflow entry");
        for (Node node : overflow) {
            Label badge = (Label) node;
            assertNotNull(badge.getTooltip(), "Overflow must expose hidden route details");
            assertFalse(badge.getTooltip().getText().isBlank());
        }
        List<Node> visible = new ArrayList<>(labels);
        visible.addAll(overflow);
        for (int i = 0; i < visible.size(); i++) {
            Bounds bounds = map.sceneToLocal(visible.get(i).localToScene(visible.get(i).getBoundsInLocal()));
            assertTrue(bounds.getMinX() >= -0.5 && bounds.getMinY() >= -0.5
                            && bounds.getMaxX() <= map.getWidth() + 0.5 && bounds.getMaxY() <= map.getHeight() + 0.5,
                    "Trace text must remain in the viewport: " + bounds);
            for (int j = i + 1; j < visible.size(); j++) {
                Bounds other = map.sceneToLocal(visible.get(j).localToScene(visible.get(j).getBoundsInLocal()));
                assertFalse(bounds.intersects(other), "Trace labels overlap: " + bounds + " / " + other);
            }
        }
    }

    private static List<Shape> paths(TileMapView map) {
        return nodes(map, "map-trace-line").stream().map(Shape.class::cast).toList();
    }

    private static List<Node> nodes(Parent parent, String styleClass) {
        List<Node> result = new ArrayList<>();
        for (Node node : parent.getChildrenUnmodifiable()) {
            if (node.getStyleClass().contains(styleClass)) result.add(node);
            else if (node instanceof Parent child) result.addAll(nodes(child, styleClass));
        }
        return result;
    }

    private static Point2D screen(TileMapView map, TileMapView.GeoPoint point) {
        return invoke(map, "geoToScreen", new Class<?>[]{double.class, double.class},
                point.latitude(), point.longitude());
    }

    private static Point2D start(Shape path) {
        if (path instanceof QuadCurve curve) return new Point2D(curve.getStartX(), curve.getStartY());
        if (path instanceof CubicCurve curve) return new Point2D(curve.getStartX(), curve.getStartY());
        if (path instanceof Line line) return new Point2D(line.getStartX(), line.getStartY());
        throw new AssertionError("Unsupported trace path " + path.getClass());
    }

    private static Point2D end(Shape path) {
        if (path instanceof QuadCurve curve) return new Point2D(curve.getEndX(), curve.getEndY());
        if (path instanceof CubicCurve curve) return new Point2D(curve.getEndX(), curve.getEndY());
        if (path instanceof Line line) return new Point2D(line.getEndX(), line.getEndY());
        throw new AssertionError("Unsupported trace path " + path.getClass());
    }

    private static String geometry(Shape path) {
        String controls = path instanceof QuadCurve curve ? curve.getControlX() + "," + curve.getControlY()
                : path instanceof CubicCurve curve ? curve.getControlX1() + "," + curve.getControlY1()
                + "," + curve.getControlX2() + "," + curve.getControlY2() : "";
        return start(path) + ";" + controls + ";" + end(path);
    }

    private static void assertNear(Point2D expected, Point2D actual) {
        assertEquals(expected.getX(), actual.getX(), 0.01, "Trace endpoint longitude anchor");
        assertEquals(expected.getY(), actual.getY(), 0.01, "Trace endpoint latitude anchor");
    }

    private static void assertWithinMap(TileMapView map, Point2D point) {
        assertTrue(point.getX() >= 0 && point.getX() <= map.getWidth()
                && point.getY() >= 0 && point.getY() <= map.getHeight(), point.toString());
    }
}
