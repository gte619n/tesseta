package com.gte619n.healthfitness.admin;

import com.gte619n.healthfitness.api.admin.AdminDrugController.DrugCatalogPort;
import com.gte619n.healthfitness.app.medication.DrugCatalogService;
import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.DrugCategory;
import com.gte619n.healthfitness.core.medication.DrugForm;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Wires the api-module admin controller to the app-module DrugCatalogService.
// api cannot depend on app per the module layering in backend/CLAUDE.md, so
// the controller declares a port interface and this adapter implements it.
@Component
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class AdminDrugAdapter implements DrugCatalogPort {

    private final DrugCatalogService service;

    public AdminDrugAdapter(DrugCatalogService service) {
        this.service = service;
    }

    @Override
    public List<Drug> findAll() {
        return service.findAll();
    }

    @Override
    public Drug createDrug(
        String name,
        List<String> aliases,
        DrugCategory category,
        DrugForm form,
        String defaultUnit,
        List<String> commonDoses,
        List<String> suggestedMarkers,
        String description
    ) {
        return service.createDrug(
            name, aliases, category, form, defaultUnit, commonDoses, suggestedMarkers, description);
    }

    @Override
    public Drug updateDrug(
        String drugId,
        String name,
        List<String> aliases,
        DrugCategory category,
        DrugForm form,
        String defaultUnit
    ) {
        return service.updateDrug(drugId, name, aliases, category, form, defaultUnit);
    }

    @Override
    public Drug mergeInto(String sourceId, String targetId) {
        return service.mergeInto(sourceId, targetId);
    }

    @Override
    public void delete(String drugId) {
        service.delete(drugId);
    }

    @Override
    public int referencingMedicationCount(String drugId) {
        return service.referencingMedicationCount(drugId);
    }

    @Override
    public String defaultImagePrompt(String drugId) {
        return service.defaultImagePrompt(drugId);
    }

    @Override
    public String regenerateImageWithPrompt(String drugId, String promptOverride) {
        return service.regenerateImageWithPrompt(drugId, promptOverride);
    }

    @Override
    public String uploadImage(String drugId, byte[] bytes, String contentType) {
        return service.uploadImage(drugId, bytes, contentType);
    }

    @Override
    public Drug selectImage(String drugId, String imageUrl) {
        return service.selectImage(drugId, imageUrl);
    }

    @Override
    public Drug deleteImage(String drugId, String imageUrl) {
        return service.deleteImage(drugId, imageUrl);
    }
}
