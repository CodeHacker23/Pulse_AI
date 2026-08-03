package org.example.pulse_ai.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.pulse_ai.config.PulseAdminProperties;
import org.example.pulse_ai.config.PulseScoutProperties;
import org.example.pulse_ai.domain.outreach.OutreachReplyService;
import org.example.pulse_ai.domain.outreach.OutreachTemplateService;
import org.example.pulse_ai.domain.product.ProductReleaseService;
import org.example.pulse_ai.domain.scout.ScoutChatImportService;
import org.example.pulse_ai.domain.scout.ScoutChatPoolService;
import org.example.pulse_ai.domain.scout.ScoutAccountService;
import org.example.pulse_ai.domain.scout.ScoutActionLogService;
import org.example.pulse_ai.domain.scout.ScoutSessionGateway;
import org.example.pulse_ai.domain.scout.ScoutSidecarHealthService;
import org.example.pulse_ai.persistence.entity.OutreachCampaignEntity;
import org.example.pulse_ai.persistence.entity.OutreachMessageTemplateEntity;
import org.example.pulse_ai.persistence.entity.ScoutAccountEntity;
import org.example.pulse_ai.persistence.entity.ScoutActionLogEntity;
import org.example.pulse_ai.persistence.repository.AdRadarHitRepository;
import org.example.pulse_ai.persistence.repository.AdWatchSourceRepository;
import org.example.pulse_ai.persistence.repository.OutreachCampaignRepository;
import org.example.pulse_ai.persistence.repository.OutreachProspectRepository;
import org.example.pulse_ai.domain.scout.SidecarAdminClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON API for Pulse Admin SPA ({@code /admin/index.html}).
 * Auth: query {@code ?token=} or header {@code X-Admin-Token}.
 */
@RestController
@RequestMapping("/admin/api")
@RequiredArgsConstructor
public class PulseAdminApiController {

    private final PulseAdminProperties adminProperties;
    private final PulseScoutProperties scoutProperties;
    private final ScoutAccountService scoutAccountService;
    private final ScoutSidecarHealthService sidecarHealth;
    private final ScoutActionLogService actionLogService;
    private final OutreachTemplateService templateService;
    private final OutreachReplyService outreachReplyService;
    private final OutreachCampaignRepository campaignRepository;
    private final ScoutSessionGateway scoutGateway;
    private final AdWatchSourceRepository watchSourceRepository;
    private final AdRadarHitRepository hitRepository;
    private final ProxyInboxService proxyInboxService;
    private final SidecarAdminClient sidecarAdmin;
    private final OutreachProspectRepository prospectRepository;
    private final ProductReleaseService productReleaseService;
    private final ScoutChatImportService chatImportService;
    private final ScoutChatPoolService chatPoolService;

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(HttpServletRequest request,
                                         @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        Map<String, Object> map = new HashMap<>();
        var health = sidecarHealth.ping();
        map.put("sidecarUrl", scoutProperties.getSidecarUrl());
        map.put("scoutEnabled", scoutProperties.isEnabled());
        map.put("sidecar", Map.of(
                "configured", health.configured(),
                "reachable", health.reachable(),
                "detail", health.detail() != null ? health.detail() : "",
                "accounts", health.accounts()
        ));
        map.put("accounts", enrichAccountRows(scoutAccountService.listAll()));
        map.put("runningCampaigns", campaignRepository.findByStatus("RUNNING").size());
        map.put("pausedCampaigns", campaignRepository.findByStatus("PAUSED").size());
        map.put("pendingProspects", prospectRepository.countByStatus("PENDING"));
        long flood = scoutAccountService.listAll().stream()
                .filter(a -> {
                    String s = a.getStatus() != null ? a.getStatus() : "";
                    return s.contains("FLOOD") || "BANNED".equals(s);
                }).count();
        map.put("problemAccounts", flood);
        var proxyList = scoutGateway.listProxies();
        int proxyTotal = proxyList.proxies() != null ? proxyList.proxies().size() : 0;
        int proxyValid = 0;
        if (proxyList.proxies() != null) {
            for (var p : proxyList.proxies()) {
                Object v = p.get("valid");
                if (v == null || Boolean.TRUE.equals(v)) {
                    proxyValid++;
                }
            }
        }
        map.put("proxies", Map.of(
                "ok", proxyList.ok(),
                "total", proxyTotal,
                "valid", proxyValid,
                "assigned", proxyList.assignments() != null ? proxyList.assignments().size() : 0,
                "error", proxyList.error() != null ? proxyList.error() : ""
        ));
        map.put("proxyInboxPath", proxyInboxService.inboxPath().toString());
        return map;
    }

    @PostMapping("/accounts/sync-sidecar")
    public Map<String, Object> syncAllSidecar(HttpServletRequest request,
                                              @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        int ok = 0;
        for (var acc : scoutAccountService.listAll()) {
            Map<String, Object> reg = sidecarAdmin.registerAccount(
                    acc.getId(), acc.getLabel(), acc.getAccountType());
            boolean done = Boolean.TRUE.equals(reg.get("ok"));
            if (done) {
                ok++;
            }
            Map<String, Object> row = new HashMap<>();
            row.put("id", acc.getId());
            row.put("ok", done);
            row.put("error", String.valueOf(reg.getOrDefault("error", "")));
            rows.add(row);
        }
        return Map.of("ok", true, "synced", ok, "total", rows.size(), "rows", rows);
    }

    @PostMapping(value = "/accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> createAccount(HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token,
                                             @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        String label = String.valueOf(body.getOrDefault("label", "")).trim();
        String type = String.valueOf(body.getOrDefault("accountType", "SENDER")).trim().toUpperCase();
        if (label.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label required");
        }
        if (!Set.of("SENDER", "OUTREACH", "PARSER", "OBSERVER").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad accountType");
        }
        int daily = 0;
        if (type.equals("SENDER") || type.equals("OUTREACH")) {
            daily = 35;
            if (body.get("dailyLimit") != null) {
                try {
                    daily = Math.max(1, Math.min(40, Integer.parseInt(String.valueOf(body.get("dailyLimit")))));
                } catch (NumberFormatException ignored) {
                    // keep 35
                }
            }
        }
        var saved = scoutAccountService.create(label, type, daily);
        Map<String, Object> sidecar = sidecarAdmin.registerAccount(
                saved.getId(), saved.getLabel(), type);
        int enrolled = 0;
        if (ScoutAccountService.isParserType(type)) {
            enrolled = chatPoolService.enrollAccount(saved.getId());
        }
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("account", saved);
        out.put("sidecar", sidecar);
        out.put("poolEnrolled", enrolled);
        boolean sideOk = Boolean.TRUE.equals(sidecar.get("ok"));
        out.put("hint", sideOk
                ? ("Карточка #" + saved.getId() + " ("
                        + (type.equals("SENDER") || type.equals("OUTREACH")
                        ? "пишущий 100+"
                        : "парсер/наблюдатель 1–99")
                        + "). Открой → сессия → прокси → «Старт» только когда В сети."
                        + (enrolled > 0 ? (" В очередь join из пула: " + enrolled + " чатов.") : ""))
                : "Карточка в БД есть, но sidecar не принял регистрацию: "
                        + String.valueOf(sidecar.getOrDefault("error", "offline"))
                        + ". Запусти scout-sidecar и создай снова / нажми «Подключить».");
        return out;
    }

    @GetMapping("/logs")
    public List<ScoutActionLogEntity> logs(HttpServletRequest request,
                                           @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return actionLogService.recent();
    }

    @GetMapping("/templates")
    public List<OutreachMessageTemplateEntity> templates(HttpServletRequest request,
                                                         @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return templateService.listAll();
    }

    @PostMapping(value = "/templates", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveTemplate(HttpServletRequest request,
                                            @RequestParam(value = "token", required = false) String token,
                                            @RequestBody Map<String, String> body) {
        requireAuth(request, token);
        var saved = templateService.upsert(
                null,
                null,
                body.getOrDefault("scenario", "INVITE"),
                body.getOrDefault("name", "custom"),
                body.getOrDefault("body", ""));
        return Map.of("ok", true, "id", saved.getId());
    }

    @GetMapping("/campaigns")
    public List<OutreachCampaignEntity> campaigns(HttpServletRequest request,
                                                  @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return campaignRepository.findAll();
    }

    @PostMapping("/accounts/{id}/pause")
    public Map<String, Object> pauseAccount(@PathVariable long id, HttpServletRequest request,
                                            @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        scoutAccountService.pause(id);
        return Map.of("ok", true, "id", id, "status", "PAUSED");
    }

    @PostMapping("/accounts/{id}/resume")
    public Map<String, Object> resumeAccount(@PathVariable long id, HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        Map<String, Object> st = sidecarAdmin.accountStatus(id);
        boolean authorized = Boolean.TRUE.equals(st.get("authorized"));
        if (!authorized) {
            String err = String.valueOf(st.getOrDefault("authError",
                    st.getOrDefault("error", "нет сессии Telegram")));
            return Map.of(
                    "ok", false,
                    "id", id,
                    "status", scoutAccountService.find(id).map(ScoutAccountEntity::getStatus).orElse("?"),
                    "error", "Нельзя ACTIVE: Telegram не вошёл (" + err + "). Сначала tdata / secrets / auth_key."
            );
        }
        scoutAccountService.resume(id);
        return Map.of("ok", true, "id", id, "status", "ACTIVE");
    }

    @PostMapping("/accounts/{id}/spambot")
    public Map<String, Object> spamBot(@PathVariable long id, HttpServletRequest request,
                                       @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        var r = scoutAccountService.runSpamBot(id);
        return Map.of("ok", r.ok(), "detail", r.detail() != null ? r.detail() : "",
                "error", r.error() != null ? r.error() : "");
    }

    @PostMapping("/accounts/{id}/enroll-pool")
    public Map<String, Object> enrollPool(@PathVariable long id, HttpServletRequest request,
                                          @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        int n = chatPoolService.enrollAccount(id);
        return Map.of("ok", true, "enrolled", n,
                "detail", n > 0
                        ? ("В очередь join: " + n + " чатов из пула")
                        : "Нечего добавлять (не PARSER/OBSERVER или пул пуст / уже в очереди)");
    }

    @PostMapping("/accounts/{id}/rotate-proxy")
    public Map<String, Object> rotateProxy(@PathVariable long id, HttpServletRequest request,
                                           @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        var r = scoutGateway.rotateProxy(id);
        return Map.of("ok", r.ok(), "detail", r.detail() != null ? r.detail() : "",
                "error", r.error() != null ? r.error() : "");
    }

    @PostMapping("/campaigns/{id}/pause")
    public Map<String, Object> pauseCampaign(@PathVariable long id, HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        campaignRepository.findById(id).ifPresent(c -> {
            c.setStatus("PAUSED");
            campaignRepository.save(c);
        });
        return Map.of("ok", true, "id", id);
    }

    @PostMapping("/campaigns/{id}/start")
    public Map<String, Object> startCampaign(@PathVariable long id, HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        campaignRepository.findById(id).ifPresent(c -> {
            c.setStatus("RUNNING");
            campaignRepository.save(c);
        });
        return Map.of("ok", true, "id", id);
    }

    @GetMapping("/proxies")
    public Map<String, Object> proxies(HttpServletRequest request,
                                       @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        var list = scoutGateway.listProxies();
        Map<String, Object> out = new HashMap<>();
        out.put("ok", list.ok());
        out.put("proxies", list.proxies());
        out.put("assignments", list.assignments());
        out.put("error", list.error());
        out.put("inboxPath", proxyInboxService.inboxPath().toString());
        out.put("inboxHint", "Сохрани список в этот файл — бот подхватит сам.");
        return out;
    }

    @PostMapping(value = "/proxies/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> importProxiesJson(HttpServletRequest request,
                                                 @RequestParam(value = "token", required = false) String token,
                                                 @RequestBody Map<String, String> body) {
        requireAuth(request, token);
        return importText(body.getOrDefault("text", ""));
    }

    @PostMapping(value = "/proxies/import-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importProxiesFile(HttpServletRequest request,
                                                 @RequestParam(value = "token", required = false) String token,
                                                 @RequestParam("file") MultipartFile file) throws Exception {
        requireAuth(request, token);
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        return importText(text);
    }

    @PostMapping("/proxies/purge")
    public Map<String, Object> purgeProxies(HttpServletRequest request,
                                            @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        var r = scoutGateway.purgeInvalidProxies();
        return Map.of("ok", r.ok(), "detail", r.detail() != null ? r.detail() : "");
    }

    @PostMapping("/proxies/check")
    public Map<String, Object> checkProxies(HttpServletRequest request,
                                            @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return sidecarAdmin.checkProxies();
    }

    @PostMapping("/proxies/pull-inbox")
    public Map<String, Object> pullInbox(HttpServletRequest request,
                                         @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return proxyInboxService.pullNow();
    }

    @PostMapping("/accounts/{id}/assign-proxy")
    public Map<String, Object> assignProxy(@PathVariable long id, HttpServletRequest request,
                                           @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        var r = scoutGateway.assignProxy(id);
        String err = r.error() != null ? r.error() : "";
        String detail = r.detail() != null ? r.detail() : "";
        // surface message in both fields — UI reads error first
        if (!r.ok() && err.isBlank() && !detail.isBlank()) {
            err = detail;
        }
        if (r.ok() && detail.isBlank() && !err.isBlank()) {
            detail = err;
        }
        return Map.of("ok", r.ok(), "detail", detail, "error", err);
    }

    @GetMapping("/accounts/{id}/me")
    public Map<String, Object> accountMe(@PathVariable long id, HttpServletRequest request,
                                         @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return sidecarAdmin.getMe(id);
    }

    @GetMapping("/accounts/{id}/status")
    public Map<String, Object> accountStatus(@PathVariable long id, HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        Map<String, Object> st = new HashMap<>(sidecarAdmin.accountStatus(id));
        if (Boolean.TRUE.equals(st.get("burned"))) {
            boolean changed = scoutAccountService.markBurned(id, String.valueOf(st.getOrDefault("rawAuthError",
                    st.getOrDefault("authError", "auth key duplicated"))));
            if (changed) {
                scoutAccountService.find(id).ifPresent(a -> {
                    if (ScoutAccountService.isParserType(a.getAccountType())) {
                        var fr = chatPoolService.handoff(id, null);
                        st.put("failover", fr.detail());
                    }
                });
            }
        }
        scoutAccountService.find(id).ifPresent(a -> {
            st.put("dbId", a.getId());
            st.put("dbLabel", a.getLabel());
            st.put("dbStatus", a.getStatus());
            st.put("dbType", a.getAccountType());
        });
        return st;
    }

    @GetMapping("/sessions/audit")
    public Map<String, Object> sessionsAudit(HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return sidecarAdmin.sessionsAudit();
    }

    @DeleteMapping("/sessions/orphan/{fileName}")
    public Map<String, Object> deleteOrphanSession(@PathVariable String fileName, HttpServletRequest request,
                                                   @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return sidecarAdmin.deleteOrphanSession(fileName);
    }

    /** Полностью убрать скаута: БД + карточка sidecar + .session. */
    @DeleteMapping("/accounts/{id}")
    public Map<String, Object> deleteAccount(@PathVariable long id, HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token,
                                             @RequestParam(value = "wipeSession", defaultValue = "true")
                                             boolean wipeSession) {
        requireAuth(request, token);
        Map<String, Object> sidecar = sidecarAdmin.deleteAccount(id, wipeSession);
        boolean dbDeleted = scoutAccountService.delete(id);
        Map<String, Object> out = new HashMap<>();
        out.put("ok", dbDeleted || Boolean.TRUE.equals(sidecar.get("ok")));
        out.put("dbDeleted", dbDeleted);
        out.put("sidecar", sidecar);
        out.put("detail", dbDeleted ? ("Скаут #" + id + " удалён") : ("В БД #" + id + " не найден"));
        return out;
    }

    /** Пометить сгоревшим вручную + авто-handoff пула на живого PARSER/OBSERVER. */
    @PostMapping("/accounts/{id}/mark-burned")
    public Map<String, Object> markBurned(@PathVariable long id, HttpServletRequest request,
                                          @RequestParam(value = "token", required = false) String token,
                                          @RequestParam(value = "toAccountId", required = false) Long toAccountId) {
        requireAuth(request, token);
        var acc = scoutAccountService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account"));
        boolean changed = scoutAccountService.markBurned(id, "помечен вручную оператором");
        Map<String, Object> out = new HashMap<>();
        out.put("ok", true);
        out.put("id", id);
        out.put("status", "BURNED");
        out.put("changed", changed);
        if (ScoutAccountService.isParserType(acc.getAccountType())) {
            var fr = chatPoolService.handoff(id, toAccountId);
            out.put("failover", Map.of(
                    "ok", fr.ok(),
                    "toAccountId", fr.toAccountId() != null ? fr.toAccountId() : "",
                    "retired", fr.retired(),
                    "enrolled", fr.enrolled(),
                    "detail", fr.detail() != null ? fr.detail() : ""
            ));
            out.put("detail", fr.detail() != null ? fr.detail() : "Помечен как сгоревший");
        } else {
            out.put("detail", "Помечен как сгоревший (SENDER — пул групп не переносится, "
                    + "проспекты/шаблоны в БД остаются)");
        }
        return out;
    }

    /**
     * Явный failover: очередь групп с from → to (или авто на первого живого watch-скаута).
     * Список scout_target_chats не теряется никогда.
     */
    @PostMapping("/accounts/{id}/failover")
    public Map<String, Object> failover(@PathVariable long id, HttpServletRequest request,
                                        @RequestParam(value = "token", required = false) String token,
                                        @RequestBody(required = false) Map<String, Object> body) {
        requireAuth(request, token);
        Long toId = null;
        if (body != null && body.get("toAccountId") != null
                && !String.valueOf(body.get("toAccountId")).isBlank()) {
            toId = Long.parseLong(String.valueOf(body.get("toAccountId")));
        }
        var fr = chatPoolService.handoff(id, toId);
        Map<String, Object> out = new HashMap<>();
        out.put("ok", fr.ok());
        out.put("fromAccountId", fr.fromAccountId() != null ? fr.fromAccountId() : id);
        out.put("toAccountId", fr.toAccountId());
        out.put("retired", fr.retired());
        out.put("enrolled", fr.enrolled());
        out.put("detail", fr.detail());
        out.put("error", fr.ok() ? null : fr.detail());
        out.put("pool", chatPoolService.stats());
        return out;
    }

    /** Снести только .session — под новый tdata того же номера. */
    @PostMapping("/accounts/{id}/wipe-session")
    public Map<String, Object> wipeSession(@PathVariable long id, HttpServletRequest request,
                                           @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        Map<String, Object> r = new HashMap<>(sidecarAdmin.wipeSession(id));
        if (Boolean.TRUE.equals(r.get("ok"))) {
            scoutAccountService.setStatus(id, "WARMING");
            r.put("detail", "Сессия снесена — загрузи новый tdata.zip");
        }
        return r;
    }

    @PostMapping("/accounts/{id}/register-sidecar")
    public Map<String, Object> registerSidecar(@PathVariable long id, HttpServletRequest request,
                                               @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        var acc = scoutAccountService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account"));
        return sidecarAdmin.registerAccount(acc.getId(), acc.getLabel(), acc.getAccountType());
    }

    @PostMapping(value = "/accounts/{id}/session", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadSession(@PathVariable long id, HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token,
                                             @RequestParam("file") MultipartFile file) throws Exception {
        requireAuth(request, token);
        scoutAccountService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account"));
        // ensure sidecar knows this id (create-only-in-db case)
        scoutAccountService.find(id).ifPresent(a ->
                sidecarAdmin.registerAccount(a.getId(), a.getLabel(), a.getAccountType()));
        Map<String, Object> r = sidecarAdmin.uploadSession(id, file.getBytes(), file.getOriginalFilename());
        if (Boolean.TRUE.equals(r.get("authorized")) || Boolean.TRUE.equals(r.get("ok"))) {
            // keep WARMING until operator resumes; still mark reachable session via hint
            r.put("next", "Сессия на месте. Дай прокси, потом «Старт» когда готов.");
        }
        return r;
    }

    @PostMapping(value = "/accounts/{id}/tdata", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadTdata(@PathVariable long id, HttpServletRequest request,
                                           @RequestParam(value = "token", required = false) String token,
                                           @RequestParam("file") MultipartFile file) throws Exception {
        requireAuth(request, token);
        var acc = scoutAccountService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account"));
        sidecarAdmin.registerAccount(acc.getId(), acc.getLabel(), acc.getAccountType());
        Map<String, Object> r = sidecarAdmin.uploadTdata(id, file.getBytes(), file.getOriginalFilename());
        if (Boolean.TRUE.equals(r.get("ok"))) {
            r.put("next", "tdata → session ок. Дай прокси → «Старт».");
        }
        return r;
    }

    @PostMapping(value = "/accounts/{id}/auth-key", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> importAuthKey(@PathVariable long id, HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token,
                                             @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        var acc = scoutAccountService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account"));
        sidecarAdmin.registerAccount(acc.getId(), acc.getLabel(), acc.getAccountType());
        String hex = String.valueOf(body.getOrDefault("authKeyHex", "")).trim();
        int dcId = 2;
        try {
            dcId = Integer.parseInt(String.valueOf(body.getOrDefault("dcId", 2)));
        } catch (NumberFormatException ignored) {
            // keep 2
        }
        if (hex.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authKeyHex required");
        }
        Map<String, Object> r = sidecarAdmin.importAuthKey(id, hex, dcId);
        if (Boolean.TRUE.equals(r.get("ok"))) {
            r.put("next", "Сессия ок. Дай прокси → «Старт».");
        }
        return r;
    }

    @PostMapping("/accounts/{id}/restore-secrets")
    public Map<String, Object> restoreSecrets(@PathVariable long id, HttpServletRequest request,
                                              @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        var acc = scoutAccountService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account"));
        sidecarAdmin.registerAccount(acc.getId(), acc.getLabel(), acc.getAccountType());
        Map<String, Object> r = sidecarAdmin.restoreFromSecrets(id);
        if (Boolean.TRUE.equals(r.get("ok"))) {
            r.put("next", "Восстановлено из accounts.secrets.json. Дай прокси → «Старт».");
            Object identity = r.get("identity");
            if (identity instanceof Map<?, ?> idn) {
                Object phone = idn.get("phone");
                Object userId = idn.get("userId");
                String ref = phone != null && !String.valueOf(phone).isBlank()
                        ? String.valueOf(phone)
                        : (userId != null ? "uid:" + userId : null);
                if (ref != null) {
                    acc.setExternalRef(ref);
                    scoutAccountService.save(acc);
                }
            }
        }
        return r;
    }

    @PostMapping(value = "/accounts/{id}/identity", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveIdentity(@PathVariable long id, HttpServletRequest request,
                                            @RequestParam(value = "token", required = false) String token,
                                            @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        var acc = scoutAccountService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account"));
        Map<String, Object> r = sidecarAdmin.saveIdentity(id, body);
        String phone = String.valueOf(body.getOrDefault("phone", "")).trim();
        if (!phone.isBlank() && !"null".equals(phone)) {
            acc.setExternalRef(phone);
            scoutAccountService.save(acc);
        }
        return r;
    }

    @PostMapping(value = "/accounts/{id}/profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> accountProfile(@PathVariable long id, HttpServletRequest request,
                                              @RequestParam(value = "token", required = false) String token,
                                              @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        return sidecarAdmin.updateProfile(id, body);
    }

    @PostMapping(value = "/accounts/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> accountPhoto(@PathVariable long id, HttpServletRequest request,
                                            @RequestParam(value = "token", required = false) String token,
                                            @RequestParam("file") MultipartFile file) throws Exception {
        requireAuth(request, token);
        return sidecarAdmin.updatePhoto(id, file.getBytes(), file.getOriginalFilename());
    }

    @GetMapping("/dialogs")
    public Map<String, Object> dialogs(HttpServletRequest request,
                                       @RequestParam(value = "token", required = false) String token,
                                       @RequestParam long accountId,
                                       @RequestParam(defaultValue = "40") int limit) {
        requireAuth(request, token);
        return sidecarAdmin.listDialogs(accountId, limit);
    }

    @PostMapping(value = "/dialogs/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> dialogResolve(HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token,
                                             @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        long accountId = Long.parseLong(String.valueOf(body.get("accountId")));
        String query = String.valueOf(body.getOrDefault("query", ""));
        return sidecarAdmin.resolvePeer(accountId, query);
    }

    @PostMapping(value = "/dialogs/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> dialogMessages(HttpServletRequest request,
                                              @RequestParam(value = "token", required = false) String token,
                                              @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        long accountId = Long.parseLong(String.valueOf(body.get("accountId")));
        String peer = String.valueOf(body.get("peer"));
        int limit = body.get("limit") != null ? Integer.parseInt(String.valueOf(body.get("limit"))) : 40;
        return sidecarAdmin.dialogMessages(accountId, peer, limit);
    }

    @PostMapping(value = "/dialogs/read", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> dialogRead(HttpServletRequest request,
                                          @RequestParam(value = "token", required = false) String token,
                                          @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        long accountId = Long.parseLong(String.valueOf(body.get("accountId")));
        String peer = String.valueOf(body.get("peer"));
        Map<String, Object> r = sidecarAdmin.markRead(accountId, peer);
        // CRM: если peer = username и есть prospect SENT → можно пометить REPLIED отдельно кнопкой
        return r;
    }

    @PostMapping(value = "/dialogs/reply", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> dialogReply(HttpServletRequest request,
                                           @RequestParam(value = "token", required = false) String token,
                                           @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        long accountId = Long.parseLong(String.valueOf(body.get("accountId")));
        String peer = String.valueOf(body.get("peer"));
        String text = String.valueOf(body.getOrDefault("text", ""));
        return sidecarAdmin.reply(accountId, peer, text);
    }

    @PostMapping(value = "/audience/parse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> audienceParse(HttpServletRequest request,
                                             @RequestParam(value = "token", required = false) String token,
                                             @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        long accountId = Long.parseLong(String.valueOf(body.get("accountId")));
        String link = String.valueOf(body.get("link"));
        int limit = body.get("limit") != null ? Integer.parseInt(String.valueOf(body.get("limit"))) : 300;
        int minScore = body.get("minScore") != null ? Integer.parseInt(String.valueOf(body.get("minScore"))) : 35;
        return sidecarAdmin.parseAudience(accountId, link, limit, minScore);
    }

    @GetMapping("/prospects/replied")
    public List<?> repliedProspects(HttpServletRequest request,
                                    @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return prospectRepository.findAll().stream()
                .filter(p -> "REPLIED".equals(p.getStatus()) || "SENT".equals(p.getStatus()))
                .sorted((a, b) -> {
                    var da = a.getRepliedAt() != null ? a.getRepliedAt() : a.getSentAt();
                    var db = b.getRepliedAt() != null ? b.getRepliedAt() : b.getSentAt();
                    if (da == null && db == null) return 0;
                    if (da == null) return 1;
                    if (db == null) return -1;
                    return db.compareTo(da);
                })
                .limit(80)
                .toList();
    }

    @GetMapping("/releases")
    public List<?> releases(HttpServletRequest request,
                            @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return productReleaseService.recent(30);
    }

    @PostMapping(value = "/releases", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveRelease(HttpServletRequest request,
                                           @RequestParam(value = "token", required = false) String token,
                                           @RequestBody Map<String, String> body) {
        requireAuth(request, token);
        var saved = productReleaseService.upsert(
                body.getOrDefault("version", ""),
                body.getOrDefault("title", ""),
                body.getOrDefault("bullets", ""),
                body.getOrDefault("category", "UPDATE"),
                body.getOrDefault("status", "READY"));
        return Map.of("ok", true, "id", saved.getId(), "version", saved.getVersion());
    }

    @GetMapping("/releases/preview")
    public Map<String, Object> releasePreview(HttpServletRequest request,
                                              @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        return productReleaseService.composeLatestReadyPatchNote()
                .map(t -> Map.<String, Object>of("ok", true, "text", t))
                .orElse(Map.of("ok", false, "error", "Нет READY-релизов"));
    }

    @GetMapping("/radar")
    public Map<String, Object> radar(HttpServletRequest request,
                                     @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        var watches = watchSourceRepository.findAll().stream().limit(40).toList();
        var hits = hitRepository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .limit(40)
                .toList();
        return Map.of("watches", watches, "hits", hits);
    }

    @PostMapping("/outreach/reply")
    public Map<String, Object> markReply(HttpServletRequest request,
                                         @RequestParam(value = "token", required = false) String token,
                                         @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        long prospectId = Long.parseLong(String.valueOf(body.get("prospectId")));
        String snippet = body.get("snippet") != null ? String.valueOf(body.get("snippet")) : null;
        boolean ok = outreachReplyService.markReplied(prospectId, snippet);
        return Map.of("ok", ok, "prospectId", prospectId);
    }

    @PostMapping(value = "/chats/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> importChats(HttpServletRequest request,
                                           @RequestParam(value = "token", required = false) String token,
                                           @RequestBody Map<String, Object> body) {
        requireAuth(request, token);
        String text = body.get("text") != null ? String.valueOf(body.get("text")) : "";
        Long accountId = null;
        if (body.get("accountId") != null && !String.valueOf(body.get("accountId")).isBlank()) {
            accountId = Long.parseLong(String.valueOf(body.get("accountId")));
        }
        return toPoolImportResponse(chatImportService.importLinks(text, accountId));
    }

    @PostMapping(value = "/chats/import-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importChatsFile(HttpServletRequest request,
                                               @RequestParam(value = "token", required = false) String token,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "accountId", required = false) Long accountId)
            throws Exception {
        requireAuth(request, token);
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        return toPoolImportResponse(chatImportService.importLinks(text, accountId));
    }

    @GetMapping("/chats/pool")
    public Map<String, Object> chatPool(HttpServletRequest request,
                                        @RequestParam(value = "token", required = false) String token) {
        requireAuth(request, token);
        Map<String, Object> out = new HashMap<>(chatPoolService.stats());
        out.put("ok", true);
        out.put("chats", chatPoolService.listChats().stream().map(c -> Map.of(
                "id", c.getId(),
                "link", c.getLink(),
                "title", c.getTitle() != null ? c.getTitle() : "",
                "status", c.getStatus(),
                "kind", c.getKind()
        )).toList());
        out.put("recent", chatPoolService.recentMemberships().stream().map(m -> Map.of(
                "id", m.getId(),
                "chatId", m.getChatId(),
                "accountId", m.getScoutAccountId(),
                "status", m.getStatus(),
                "attempts", m.getAttempts(),
                "error", m.getLastError() != null ? m.getLastError() : ""
        )).toList());
        return out;
    }

    private Map<String, Object> toPoolImportResponse(ScoutChatImportService.ImportResult r) {
        Map<String, Object> out = new HashMap<>();
        out.put("ok", r.error() == null);
        out.put("error", r.error());
        out.put("detail", r.detail());
        out.put("mode", "pool");
        out.put("accountId", r.accountId());
        out.put("accountLabel", r.accountLabel());
        out.put("total", r.total());
        out.put("queued", r.ok());
        out.put("joined", 0);
        out.put("failed", r.fail());
        out.put("pool", chatPoolService.stats());
        out.put("lines", r.lines().stream()
                .map(l -> Map.of(
                        "link", l.link(),
                        "ok", l.ok(),
                        "title", l.title() != null ? l.title() : "",
                        "error", l.error() != null ? l.error() : ""))
                .toList());
        return out;
    }

    private Map<String, Object> importText(String text) {
        var result = scoutGateway.importProxies(text != null ? text : "");
        Map<String, Object> out = new HashMap<>();
        out.put("ok", result.ok());
        out.put("added", result.added());
        out.put("total", result.total());
        out.put("valid", result.valid());
        out.put("error", result.error());
        return out;
    }

    /** БД-статус + живой TG (чтобы ACTIVE без сессии не выглядел как «всё ок»). */
    private List<Map<String, Object>> enrichAccountRows(List<ScoutAccountEntity> accounts) {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (ScoutAccountEntity a : accounts) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("label", a.getLabel());
            m.put("accountType", a.getAccountType());
            m.put("externalRef", a.getExternalRef());
            m.put("status", a.getStatus());
            m.put("dailyLimit", a.getDailyLimit());
            m.put("sentToday", a.getSentToday());
            m.put("spambotToday", a.getSpambotToday());
            m.put("spambotMax", ScoutAccountService.SPAMBOT_DAILY_MAX);
            m.put("lastError", a.getLastError());
            m.put("senderBand", ScoutAccountService.isSenderType(a.getAccountType()));
            try {
                Map<String, Object> st = sidecarAdmin.accountStatus(a.getId());
                boolean auth = Boolean.TRUE.equals(st.get("authorized"));
                m.put("tgAuthorized", auth);
                boolean burned = Boolean.TRUE.equals(st.get("burned"));
                m.put("burned", burned);
                if (burned) {
                    boolean justBurned = scoutAccountService.markBurned(a.getId(), String.valueOf(st.getOrDefault("rawAuthError",
                            st.getOrDefault("authError", "auth key duplicated"))));
                    m.put("status", "BURNED");
                    if (justBurned && ScoutAccountService.isParserType(a.getAccountType())) {
                        var fr = chatPoolService.handoff(a.getId(), null);
                        m.put("failover", fr.detail());
                    }
                }
                Object me = st.get("me");
                if (me instanceof Map<?, ?> mm) {
                    Object phone = mm.get("phone");
                    if (phone != null && !String.valueOf(phone).isBlank()) {
                        m.put("phone", String.valueOf(phone));
                    }
                }
                Object idn = st.get("identity");
                if (idn instanceof Map<?, ?> idm) {
                    if (m.get("phone") == null && idm.get("phone") != null) {
                        m.put("phone", String.valueOf(idm.get("phone")));
                    }
                    if (idm.get("userId") != null) {
                        m.put("tgUserId", idm.get("userId"));
                    }
                }
                if (!auth && st.get("authError") != null) {
                    m.put("tgError", String.valueOf(st.get("authError")));
                }
            } catch (Exception ex) {
                m.put("tgAuthorized", false);
                m.put("tgError", ex.getMessage());
            }
            rows.add(m);
        }
        return rows;
    }

    private void requireAuth(HttpServletRequest request, String tokenParam) {
        if (!adminProperties.isWebEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String expected = adminProperties.getWebToken();
        String header = request.getHeader("X-Admin-Token");
        String token = tokenParam != null ? tokenParam : header;
        if (expected == null || expected.isBlank() || token == null || !expected.equals(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid admin token");
        }
    }
}
