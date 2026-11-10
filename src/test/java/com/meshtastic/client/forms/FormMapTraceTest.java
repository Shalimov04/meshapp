package com.meshtastic.client.forms;

import com.meshtastic.client.TraceMapTestSupport;
import com.meshtastic.client.components.chat.TracerouteView;
import com.meshtastic.client.components.map.TileMapView;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.MessageDbService;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.MeshProtos;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FormMapTraceTest extends TraceMapTestSupport {
    private static final int OWNER = 0x71000001;
    private static final int MIDDLE = 0x71000002;
    private static final int TARGET = 0x71000003;
    private static final int OTHER = 0x71000004;

    @Test
    void storedTargetIdWinsWhenSavedDisplayNameWasRenamedOrDuplicated() {
        fx(() -> {
            NodeData target = node(TARGET, "Renamed target", 51.04, 12.08);
            FormMap form = form(OWNER, node(OWNER, "Owner", 51, 12),
                    node(OTHER, "Saved target", 0, 0), target);
            long id = save(OWNER, "Saved target", MeshProtos.RouteDiscovery.newBuilder()
                    .addSnrTowards(20).build(), null);
            form.showTracerouteResult(id);

            List<TileMapView.TraceSegment> segments = segments(form);
            assertEquals(1, segments.size());
            assertEquals(point(target), segments.getFirst().to(),
                    "The saved ID identifies the renamed target even when another node has its old name");
            return null;
        });
    }

    @Test
    void protobufIntermediateIdsSurviveDuplicateNamesAndLaterRenames() {
        fx(() -> {
            NodeData middle = node(MIDDLE, "Shared name", 51.02, 12.04);
            FormMap form = form(OWNER, node(OWNER, "Owner", 51, 12),
                    node(OTHER, "Shared name", 0, 0), middle, node(TARGET, "Target", 51.04, 12.08));
            long id = save(OWNER, "Target", completeRoute(), null);
            form.showTracerouteResult(id);
            assertEquals(4, segments(form).size());
            assertEquals(point(middle), segments(form).getFirst().to());

            middle.setLongName("Renamed intermediate");
            middle.setShortName("New");
            invoke(form, "refreshSelectedTraceOverlay", new Class<?>[]{boolean.class}, false);
            assertEquals(4, segments(form).size());
            assertEquals(point(middle), segments(form).getFirst().to(),
                    "Refreshing an already selected trace must resolve the original protobuf ID");
            return null;
        });
    }

    @Test
    void storedOwnerIdentityWinsOverCurrentLocalAliasForProtobufAndLegacyRows() {
        fx(() -> {
            NodeData owner = node(OWNER, "Saved owner", 51, 12);
            FormMap form = form(OTHER, owner, node(OTHER, "Current owner", 52, 13),
                    node(TARGET, "Target", 51.04, 12.08));
            long protobufId = save(OWNER, "Target", MeshProtos.RouteDiscovery.newBuilder()
                    .addSnrTowards(20).build(), null);
            selectStoredRow(form, protobufId, OWNER);
            assertEquals(point(owner), segments(form).getFirst().from());

            long legacyId = save(OWNER, "Old target", null,
                    TracerouteView.TRACEROUTE_PREFIX + "Old target\nForward: Me →5.0dB→ Old target");
            selectStoredRow(form, legacyId, OWNER);
            assertEquals(1, segments(form).size());
            assertEquals(point(owner), segments(form).getFirst().from());
            assertEquals(new TileMapView.GeoPoint(51.04, 12.08), segments(form).getFirst().to());
            return null;
        });
    }

    @Test
    void forwardOnlyResultsDoNotInventReverseMeasurements() {
        fx(() -> {
            FormMap form = form(OWNER, node(OWNER, "Owner", 51, 12),
                    node(MIDDLE, "Middle", 51.02, 12.04), node(TARGET, "Target", 51.04, 12.08));
            form.showTracerouteResult(save(OWNER, "Target", MeshProtos.RouteDiscovery.newBuilder()
                    .addRoute(MIDDLE).addSnrTowards(20).addSnrTowards(40).build(), null));
            assertEquals(2, segments(form).size());
            assertTrue(segments(form).stream().noneMatch(TileMapView.TraceSegment::reverse));
            assertEquals(List.of(5.0, 10.0), segments(form).stream().map(TileMapView.TraceSegment::snr).toList());
            return null;
        });
    }

    @Test
    void ambiguousLegacyIntermediateNameIsReportedAsMissingInsteadOfChoosingAnArbitraryNode() {
        fx(() -> {
            FormMap form = form(OWNER, node(OWNER, "Owner", 51, 12),
                    node(MIDDLE, "Duplicate", 51.02, 12.04), node(OTHER, "Duplicate", 52, 13),
                    node(TARGET, "Target", 51.04, 12.08));
            form.showTracerouteResult(save(OWNER, "Target", null,
                    TracerouteView.TRACEROUTE_PREFIX + "Target\nForward: Me →5.0dB→ Duplicate →10.0dB→ Target"));
            assertEquals(1, segments(form).size());
            TileMapView.TraceSegment gap = segments(form).getFirst();
            assertEquals(new TileMapView.GeoPoint(51, 12), gap.from());
            assertEquals(new TileMapView.GeoPoint(51.04, 12.08), gap.to());
            assertEquals(1, gap.hiddenHopCount());
            return null;
        });
    }

    @Test
    void skippedIntermediateIsCountedOnceAndGapIsExplicitWithMeanSnr() {
        fx(() -> {
            FormMap form = form(OWNER, node(OWNER, "Owner", 51, 12),
                    node(MIDDLE, "Missing position", 0, 0), node(TARGET, "Target", 51.04, 12.08));
            form.showTracerouteResult(save(OWNER, "Target", MeshProtos.RouteDiscovery.newBuilder()
                    .addRoute(MIDDLE).addSnrTowards(20).addSnrTowards(40).build(), null));
            assertEquals(1, segments(form).size());
            TileMapView.TraceSegment gap = segments(form).getFirst();
            assertEquals(7.5, gap.snr());
            assertEquals(1, gap.hiddenHopCount());
            assertTrue(gap.averagedSnr());
            assertTrue(gap.signalText().toLowerCase().contains("mean"), gap.signalText());
            assertTrue(gap.signalText().toLowerCase().contains("without coordinates"), gap.signalText());
            String summary = FormMapTraceTest.<Label>field(form, "traceSummaryLabel").getText();
            assertTrue(summary.contains(I18n.t("map.trace.skippedPoints", 1)), summary);
            return null;
        });
    }

    @Test
    void selectedTraceSummaryRemainsVisibleAfterZooming() {
        fx(() -> {
            FormMap form = form(OWNER, node(OWNER, "Owner", 51, 12),
                    node(MIDDLE, "Middle", 51.02, 12.04), node(TARGET, "Target", 51.04, 12.08));
            form.showTracerouteResult(save(OWNER, "Target", completeRoute(), null));
            Label summaryLabel = field(form, "traceSummaryLabel");
            String summary = summaryLabel.getText();
            assertFalse(summary.isBlank());
            assertTrue(summaryLabel.isVisible());
            TileMapView map = field(form, "mapView");
            map.zoomOut();
            assertEquals(summary, summaryLabel.getText());
            assertTrue(summaryLabel.isVisible());
            map.zoomIn();
            assertEquals(summary, summaryLabel.getText());
            assertTrue(summaryLabel.isVisible());
            assertEquals(4, segments(form).size());
            return null;
        });
    }

    private static FormMap form(int local, NodeData... nodes) {
        FormMap form = new FormMap();
        FormMapTraceTest.<TileMapView>field(form, "mapView").setOfflineOnly(true);
        DeviceState state = new DeviceState();
        state.setMyNodeNum(local);
        for (NodeData node : nodes) state.getNodeDb().put(node.getNodeNum(), node);
        field(form, "state", state);
        field(form, "localNodeNum", local);
        new Scene(form, 1280, 820);
        form.resize(1280, 820);
        form.applyCss();
        form.layout();
        invoke(form, "renderMarkers", new Class<?>[]{List.class}, List.of(nodes));
        return form;
    }

    private static MeshProtos.RouteDiscovery completeRoute() {
        return MeshProtos.RouteDiscovery.newBuilder().addRoute(MIDDLE)
                .addSnrTowards(20).addSnrTowards(40).addRouteBack(MIDDLE)
                .addSnrBack(12).addSnrBack(24).build();
    }

    private static long save(int owner, String targetName, MeshProtos.RouteDiscovery route, String text) {
        return MessageDbService.getInstance().saveTracerouteResult(nodeId(owner), "DM", nodeId(TARGET),
                "regression-test", UUID.randomUUID().toString(), 0, TARGET, nodeId(TARGET), targetName,
                TARGET, nodeId(TARGET), route == null ? null : route.toByteArray(), text, 1_700_000_000L);
    }

    private static void selectStoredRow(FormMap form, long id, int owner) {
        MessageDbService.TracerouteResultRecord row = MessageDbService.getInstance()
                .loadTracerouteResult(id, nodeId(owner)).orElseThrow();
        Optional<?> parsed = invoke(form, "parseTraceRecord",
                new Class<?>[]{MessageDbService.TracerouteResultRecord.class}, row);
        Map<Long, Object> selected = field(form, "selectedTraces");
        selected.clear();
        selected.put(id, parsed.orElseThrow());
        invoke(form, "refreshSelectedTraceOverlay", new Class<?>[]{boolean.class}, true);
    }

    private static List<TileMapView.TraceSegment> segments(FormMap form) {
        return field(FormMapTraceTest.<TileMapView>field(form, "mapView"), "traceSegments");
    }

    private static NodeData node(int number, String name, double lat, double lon) {
        NodeData node = new NodeData(number);
        node.setNodeId(nodeId(number));
        node.setLongName(name);
        node.setShortName(name);
        node.setLatitude(lat);
        node.setLongitude(lon);
        return node;
    }

    private static String nodeId(int number) {
        return String.format("!%08x", number);
    }

    private static TileMapView.GeoPoint point(NodeData node) {
        return new TileMapView.GeoPoint(node.getLatitude(), node.getLongitude());
    }
}
