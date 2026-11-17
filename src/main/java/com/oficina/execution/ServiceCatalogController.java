package com.oficina.execution;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Document("service_catalog")
record CatalogService(@Id String id, String branchId, String serviceId, String description, BigDecimal unitPrice) {
    static String key(UUID branch, UUID service) { return branch + ":" + service; }
}

interface CatalogServiceRepository extends MongoRepository<CatalogService, String> { }

@RestController
@RequestMapping("/api/branches/{branch}/services/{service}/catalog")
class ServiceCatalogController {
    record Request(@NotBlank @Size(max = 200) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal unitPrice) { }
    private final CatalogServiceRepository catalog;
    ServiceCatalogController(CatalogServiceRepository catalog) { this.catalog = catalog; }

    @PutMapping
    CatalogService put(@PathVariable UUID branch, @PathVariable UUID service, @Valid @RequestBody Request request) {
        return catalog.save(new CatalogService(CatalogService.key(branch, service), branch.toString(), service.toString(),
                request.description().trim(), request.unitPrice()));
    }

    @GetMapping
    CatalogService get(@PathVariable UUID branch, @PathVariable UUID service) {
        return catalog.findById(CatalogService.key(branch, service))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
    }

    @PutMapping("/import")
    CatalogService importService(@PathVariable UUID branch, @PathVariable UUID service, @Valid @RequestBody Request request) {
        var candidate = new CatalogService(CatalogService.key(branch, service), branch.toString(), service.toString(),
                request.description().trim(), request.unitPrice());
        try { return catalog.insert(candidate); }
        catch (org.springframework.dao.DuplicateKeyException conflict) {
            var existing = catalog.findById(candidate.id()).orElseThrow(() -> conflict);
            if (!existing.description().equals(candidate.description()) || existing.unitPrice().compareTo(candidate.unitPrice()) != 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Catalog import conflicts with existing service");
            return existing;
        }
    }
}
