package com.oficina.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PartCatalogControllerTest {
    @Test void catalogIsScopedByBranchAndReturnsSavedPrice() {
        var repository = mock(CatalogPartRepository.class);
        when(repository.save(any(CatalogPart.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var controller = new PartCatalogController(repository);
        UUID branch = UUID.randomUUID(), part = UUID.randomUUID(), other = UUID.randomUUID();
        var saved = controller.put(branch, part, new PartCatalogController.Request("  Filtro  ", new BigDecimal("15.50")));
        when(repository.findById(CatalogPart.key(branch, part))).thenReturn(Optional.of(saved));
        assertEquals("Filtro", controller.get(branch, part).description());
        assertEquals(new BigDecimal("15.50"), controller.get(branch, part).unitPrice());
        assertThrows(ResponseStatusException.class, () -> controller.get(other, part));
    }
}
