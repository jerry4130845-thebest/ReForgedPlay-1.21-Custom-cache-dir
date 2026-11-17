package com.replaymod.render.metadata;

import com.replaymod.core.ReplayMod;
import com.replaymod.render.RenderSettings;
import de.johni0702.minecraft.gui.utils.lwjgl.Dimension;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static com.replaymod.render.ReplayModRender.LOGGER;

public class MetadataInjector {

    private static final String STITCHING_SOFTWARE = "Minecraft ReplayMod";

    /*
     * The Spherical XML is a modified version of https://github.com/google/spatial-media/tree/master/360-Videos-Metadata
     */
    private static final String SPHERICAL_XML_HEADER =
            "<?xml version=\"1.0\"?> " +
            "<rdf:SphericalVideo xmlns:rdf=" +
            "\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:GSpherical=\"http://ns.google.com/videos/1.0/spherical/\"> ";
    private static final String SPHERICAL_XML_CONTENTS =
            "<GSpherical:Spherical>true</GSpherical:Spherical> " +
            "<GSpherical:Stitched>true</GSpherical:Stitched> " +
            "<GSpherical:StitchingSoftware>"+STITCHING_SOFTWARE+"</GSpherical:StitchingSoftware> " +
            "<GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType> ";
    private static final String SPHERICAL_CROP_XML =
            "<GSpherical:FullPanoWidthPixels>%d</GSpherical:FullPanoWidthPixels> " +
            "<GSpherical:FullPanoHeightPixels>%d</GSpherical:FullPanoHeightPixels> " +
            "<GSpherical:CroppedAreaImageWidthPixels>%d</GSpherical:CroppedAreaImageWidthPixels> " +
            "<GSpherical:CroppedAreaImageHeightPixels>%d</GSpherical:CroppedAreaImageHeightPixels> " +
            "<GSpherical:CroppedAreaLeftPixels>%d</GSpherical:CroppedAreaLeftPixels> " +
            "<GSpherical:CroppedAreaTopPixels>%d</GSpherical:CroppedAreaTopPixels> ";
    private static final String STEREO_XML_CONTENTS = "<GSpherical:StereoMode>top-bottom</GSpherical:StereoMode>";
    private static final String SPHERICAL_XML_FOOTER = "</rdf:SphericalVideo>";

    private static final String XML_MONO_METADATA = SPHERICAL_XML_HEADER + SPHERICAL_XML_CONTENTS
            + SPHERICAL_CROP_XML + SPHERICAL_XML_FOOTER;
    private static final String XML_STEREO_METADATA = SPHERICAL_XML_HEADER + SPHERICAL_XML_CONTENTS
            + SPHERICAL_CROP_XML + STEREO_XML_CONTENTS + SPHERICAL_XML_FOOTER;

    /**
     * These bytes are taken from the variable 'spherical_uuid_id'
     * in https://github.com/google/spatial-media/tree/master/360-Videos-Metadata
     */
    private static final byte[] UUID_BYTES = new byte[] {
            (byte)0xff, (byte)0xcc, (byte)0x82, (byte)0x63,
            (byte)0xf8, (byte)0x55, (byte)0x4a, (byte)0x93,
            (byte)0x88, (byte)0x14, (byte)0x58, (byte)0x7a,
            (byte)0x02, (byte)0x52, (byte)0x1f, (byte)0xdd
    };

    /** Compatibility best-effort facade. Logs are NOT a metadata success receipt. */
    public static void injectMetadata(RenderSettings.RenderMethod renderMethod, File videoFile,
                                      int videoWidth, int videoHeight,
                                      int sphericalFovX, int sphericalFovY) {
        try {
            VideoMetadataTask.Result result = injectMetadataWithResult(renderMethod, videoFile,
                    videoWidth, videoHeight, sphericalFovX, sphericalFovY);
            if (!result.metadataPresent() || !result.cleanupDiagnostics().isEmpty()) {
                LOGGER.warn("Spherical metadata best-effort result: {}", result);
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("Spherical metadata best-effort facade failed; no success receipt", failure);
        }
    }

    /** Project-owned explicit receipt API; caller must establish writer readiness. */
    public static VideoMetadataTask.Result injectMetadataWithResult(
            RenderSettings.RenderMethod renderMethod, File videoFile,
            int videoWidth, int videoHeight, int sphericalFovX, int sphericalFovY) throws IOException {
        String xmlString;
        switch (renderMethod) {
            case EQUIRECTANGULAR:
                xmlString = XML_MONO_METADATA;
                break;
            case ODS:
                xmlString = XML_STEREO_METADATA;
                break;
            default:
                throw new IllegalArgumentException("Invalid render method");
        }

        Dimension original = getOriginalDimensions(videoWidth, videoHeight, sphericalFovX, sphericalFovY);
        String metadata = String.format(xmlString,
                original.getWidth(), original.getHeight(), videoWidth, videoHeight,
                (original.getWidth() - videoWidth) / 2, (original.getHeight() - videoHeight) / 2);
        Path selectedTempRoot = ReplayMod.instance.folders.snapshotVideoMetadataTempRoot();
        // UserBox(userType=UUID16), data=XML only; never prefix the UUID to XML data.
        return VideoMetadataTask.apply(videoFile.toPath(), selectedTempRoot,
                UUID_BYTES.clone(), metadata.getBytes(StandardCharsets.UTF_8));
    }

    private static Dimension getOriginalDimensions(int videoWidth, int videoHeight,
                                                   int sphericalFovX, int sphericalFovY) {
        if (sphericalFovX < 360) {
            videoWidth = Math.round(videoWidth * 360 / (float) sphericalFovX);
        }

        if (sphericalFovY < 180) {
            videoHeight = Math.round(videoHeight * 180 / (float) sphericalFovY);
        }

        return new Dimension(videoWidth, videoHeight);
    }

}