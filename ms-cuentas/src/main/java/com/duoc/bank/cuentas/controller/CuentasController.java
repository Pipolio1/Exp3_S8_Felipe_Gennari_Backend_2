package com.duoc.bank.cuentas.controller;

import com.duoc.bank.common.dto.SaldoCuentaDTO;
import com.duoc.bank.common.exception.ErrorResponse;
import com.duoc.bank.cuentas.dto.CuentaCompletaDTO;
import com.duoc.bank.cuentas.dto.PaginaDTO;
import com.duoc.bank.cuentas.dto.StatsDTO;
import com.duoc.bank.cuentas.mapper.CuentasMapper;
import com.duoc.bank.cuentas.service.CuentasService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API publica del microservicio de cuentas: listado paginado, detalle de la
 * cuenta vigente, saldo y estadisticas. Las operaciones anuales no se exponen
 * aqui: pertenecen a otro microservicio.
 *
 * El controlador solo orquesta HTTP: la logica de negocio vive en
 * {@link CuentasService} y la conversion a DTO en {@link CuentasMapper}.
 * Los errores los resuelve de forma centralizada {@code GlobalExceptionHandler}.
 */
@Validated
@RestController
@RequestMapping("/api/cuentas")
@Tag(name = "Cuentas", description = "Saldos, listado paginado y estadisticas de las cuentas vigentes")
public class CuentasController {

    private final CuentasService cuentasService;

    public CuentasController(CuentasService cuentasService) {
        this.cuentasService = cuentasService;
    }

    /** Listado paginado de cuentas distintas (registro mas reciente de cada una). */
    @GetMapping
    @Operation(summary = "Listado paginado de cuentas vigentes")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina de cuentas vigentes"),
            @ApiResponse(responseCode = "400", description = "Parametros page/size fuera de rango",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "API key invalida o token ausente/invalido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Token sin permiso CONSULTA",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public PaginaDTO<CuentaCompletaDTO> listarCuentas(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("cuentaId"));
        return PaginaDTO.desde(cuentasService.listarCuentasVigentes(pageable), CuentasMapper::toCuentaCompletaDTO);
    }

    /** Detalle de la cuenta vigente (sin operaciones anuales: son de otro servicio). */
    @GetMapping("/{cuentaId}")
    @Operation(summary = "Detalle de la cuenta vigente")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Detalle de la cuenta vigente"),
            @ApiResponse(responseCode = "401", description = "API key invalida o token ausente/invalido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Token sin permiso CONSULTA",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "La cuenta no existe",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public CuentaCompletaDTO detalleCuenta(@PathVariable Long cuentaId) {
        return CuentasMapper.toCuentaCompletaDTO(cuentasService.obtenerCuentaVigente(cuentaId));
    }

    /** Saldo vigente de una cuenta. */
    @GetMapping("/{cuentaId}/saldo")
    @Operation(summary = "Saldo vigente de una cuenta")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saldo vigente de la cuenta"),
            @ApiResponse(responseCode = "401", description = "API key invalida o token ausente/invalido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Token sin permiso CONSULTA",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "La cuenta no existe",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public SaldoCuentaDTO obtenerSaldo(@PathVariable Long cuentaId) {
        return cuentasService.obtenerSaldo(cuentaId);
    }

    /** Estadisticas del servicio: total de cuentas distintas (requiere permiso REPORTES). */
    @GetMapping("/stats")
    @Operation(summary = "Estadisticas de cuentas (requiere permiso REPORTES)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Total de cuentas distintas"),
            @ApiResponse(responseCode = "401", description = "API key invalida o token ausente/invalido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Token sin permiso REPORTES",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public StatsDTO stats() {
        return new StatsDTO(cuentasService.contarCuentasDistintas());
    }
}
