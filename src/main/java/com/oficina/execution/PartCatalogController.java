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

@Document("part_catalog")
record CatalogPart(@Id String id, String branchId, String partId, String description, BigDecimal unitPrice) {
    static String key(UUID branch, UUID part) { return branch + ":" + part; }
}

interface CatalogPartRepository extends MongoRepository<CatalogPart, String> { }

/** Branch-scoped catalog; stock is managed independently by StockService. */
@RestController
@RequestMapping("/api/branches/{branch}/parts/{part}/catalog")
class PartCatalogController {
    record Request(@NotBlank @Size(max = 200) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 13, fraction = 2) BigDecimal unitPrice) { }
    private final CatalogPartRepository catalog;
    PartCatalogController(CatalogPartRepository catalog) { this.catalog = catalog; }

    @PutMapping
    CatalogPart put(@PathVariable UUID branch, @PathVariable UUID part, @Valid @RequestBody Request request) {
        return catalog.save(new CatalogPart(CatalogPart.key(branch, part), branch.toString(), part.toString(),
                request.description().trim(), request.unitPrice()));
    }

    @GetMapping
    CatalogPart get(@PathVariable UUID branch, @PathVariable UUID part) {
        return catalog.findById(CatalogPart.key(branch, part))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Part not found"));
    }

    @PutMapping("/import")
    CatalogPart importPart(@PathVariable UUID branch, @PathVariable UUID part, @Valid @RequestBody Request request) {
        var candidate = new CatalogPart(CatalogPart.key(branch, part), branch.toString(), part.toString(),
                request.description().trim(), request.unitPrice());
        try { return catalog.insert(candidate); }
        catch (org.springframework.dao.DuplicateKeyException conflict) {
            var existing = catalog.findById(candidate.id()).orElseThrow(() -> conflict);
            if (!existing.description().equals(candidate.description()) || existing.unitPrice().compareTo(candidate.unitPrice()) != 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Catalog import conflicts with existing part");
            return existing;
        }
    }
}
