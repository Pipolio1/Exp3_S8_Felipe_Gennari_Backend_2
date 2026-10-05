package com.duoc.bank.transacciones.controller;

import com.duoc.bank.common.exception.ErrorResponse;
import com.duoc.bank.transacciones.dto.PaginaDTO;
import com.duoc.bank.transacciones.dto.StatsDTO;
import com.duoc.bank.transacciones.dto.TransaccionDTO;
import com.duoc.bank.transacciones.dto.TransaccionLigeraDTO;
import com.duoc.bank.transacciones.mapper.TransaccionesMapper;
import com.duoc.bank.transacciones.service.TransaccionesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador REST del microservicio de transacciones. Entrega el historial
 * paginado con filtros, los movimientos recientes en formato ligero y un
 * resumen estadistico del historial.
 *
 * El controlador solo orquesta HTTP: el acceso a datos vive en
 * {@link TransaccionesService} y la conversion a DTO en {@link TransaccionesMapper}.
 * Los errores los resuelve de forma centralizada {@code GlobalExceptionHandler}.
 */
@Validated
@RestController
@RequestMapping("/api/transacciones")
@Tag(name = "Transacciones", description = "Historial, anomalias y movimientos recientes")
public class TransaccionesController {

    private final TransaccionesService transaccionesService;

    public TransaccionesController(TransaccionesService transaccionesService) {
        this.transaccionesService = transaccionesService;
    }

    /** Historial paginado de transacciones, con filtros opcionales por tipo y anomalia. */
    @GetMapping
    @Operation(summary = "Historial paginado de transacciones con filtros opcionales")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina del historial de transacciones"),
            @ApiResponse(responseCode = "400", description = "Parametros page/size fuera de rango",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "API key invalida o token ausente/invalido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Token sin permiso CONSULTA",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public PaginaDTO<TransaccionDTO> listarTransacciones(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) Boolean anomalia) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "fecha", "registroId"));
        return PaginaDTO.desde(
                transaccionesService.buscarTransacciones(tipo, anomalia, pageable),
                TransaccionesMapper::toTransaccionDTO);
    }

    /** Ultimas 10 transacciones registradas, en formato ligero. */
    @GetMapping("/recientes")
    @Operation(summary = "Ultimas 10 transacciones, de la mas reciente a la mas antigua")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ultimas 10 transacciones en formato ligero"),
            @ApiResponse(responseCode = "401", description = "API key invalida o token ausente/invalido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Token sin permiso CONSULTA",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public List<TransaccionLigeraDTO> listarRecientes() {
        return transaccionesService.listarRecientes().stream()
                .map(TransaccionesMapper::toTransaccionLigeraDTO)
                .toList();
    }

    /** Resumen estadistico del historial (requiere permiso REPORTES). */
    @GetMapping("/stats")
    @Operation(summary = "Totales del historial: transacciones y anomalias (requiere permiso REPORTES)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Totales de transacciones y anomalias"),
            @ApiResponse(responseCode = "401", description = "API key invalida o token ausente/invalido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Token sin permiso REPORTES",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StatsDTO stats() {
        return new StatsDTO(transaccionesService.contarTransacciones(),
                transaccionesService.contarAnomalias());
    }
}
