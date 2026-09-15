package com.gte619n.healthfitness.core.gym;

import com.gte619n.healthfitness.core.equipment.ParsedEquipment;
import java.io.InputStream;
import java.util.List;

/**
 * IMPL-GYM-003: detection port. Given a walkthrough video, return the distinct
 * equipment as {@link ParsedEquipment} — the SAME type the text bulk-import parser
 * returns — so downstream catalog matching/confirm is reused unchanged. The
 * adapter uploads the video to the Gemini Files API and calls a structured tool.
 */
public interface VideoEquipmentDetector {
    List<ParsedEquipment> detect(InputStream video, long length, String mimeType);
}
