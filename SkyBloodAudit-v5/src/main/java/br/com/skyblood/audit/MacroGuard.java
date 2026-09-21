package br.com.skyblood.audit;

import org.bukkit.entity.Player;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detector estatístico de macro/autoclicker/script.
 *
 * Por que não é "a cada X segundos o jogador executa uma função":
 * um contador de intervalo fixo é trivialmente evadido por qualquer script que
 * randomize o delay entre ações (ex.: alternar entre 1s e 5s em loop). Em vez disso,
 * este módulo mede a JANELA de ações recentes de cada jogador por categoria (ataque,
 * interação, quebra de bloco, clique de GUI) e combina quatro sinais independentes:
 *
 *  1. Baixa variância (coeficiente de variação) sustentada — humanos não mantêm
 *     ritmo quase perfeito por dezenas de ações seguidas.
 *  2. Repetição do mesmo valor de intervalo — muitos scripts "simples" caem aqui.
 *  3. Ciclagem entre poucos valores discretos — pega justamente o caso citado pelo
 *     usuário: um script que alterna entre um pequeno conjunto de delays "aleatórios"
 *     (ex.: 1s, 5s, 1s, 5s...) ainda colapsa estatisticamente em poucos buckets.
 *  4. Alinhamento a múltiplos redondos de 100ms — comum em scripts baseados em tick/timer.
 *
 * Um score de confiança combina os quatro sinais com pesos configuráveis. Um alerta só
 * é disparado quando o score ultrapassa o limiar em janelas CONSECUTIVAS (não uma
 * flutuação isolada) e respeita um cooldown por jogador/ação. Tudo ajustável via config.yml.
 */
public final class MacroGuard {
    private final SkyBloodAudit core;
    private final Map<String, Deque<Long>> history = new ConcurrentHashMap<>();
    private final Map<String, Integer> sinceEval = new ConcurrentHashMap<>();
    private final Map<String, Integer> streak = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAlert = new ConcurrentHashMap<>();

    MacroGuard(SkyBloodAudit core) { this.core = core; }

    /** Chamado a cada ocorrência da ação monitorada (ataque, interação, quebra de bloco, clique). */
    public void pulse(Player p, String action) {
        if (!core.getConfig().getBoolean("macro-protection.enabled", true)) return;
        if (!core.getConfig().getBoolean("macro-protection.actions." + action, true)) return;

        String key = p.getUniqueId() + "|" + action;
        Deque<Long> q = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        List<Long> snapshot;
        synchronized (q) {
            q.addLast(now);
            int window = core.getConfig().getInt("macro-protection.window-size", 60);
            while (q.size() > window) q.pollFirst();

            int every = Math.max(1, core.getConfig().getInt("macro-protection.evaluate-every-n-actions", 8));
            int c = sinceEval.merge(key, 1, Integer::sum);
            if (c < every) return;
            sinceEval.put(key, 0);
            snapshot = new ArrayList<>(q);
        }
        evaluate(p, action, key, snapshot);
    }

    private void evaluate(Player p, String action, String key, List<Long> ts) {
        int minSamples = core.getConfig().getInt("macro-protection.min-samples", 24);
        if (ts.size() < minSamples) return;

        long spanMs = ts.get(ts.size() - 1) - ts.get(0);
        long minSpanMs = core.getConfig().getLong("macro-protection.min-span-seconds", 40) * 1000L;
        if (spanMs < minSpanMs) return; // evita falso positivo em rajadas curtas legítimas

        List<Long> iv = new ArrayList<>(ts.size() - 1);
        for (int i = 1; i < ts.size(); i++) iv.add(ts.get(i) - ts.get(i - 1));

        double mean = iv.stream().mapToLong(Long::longValue).average().orElse(0);
        if (mean <= 0) return;
        double variance = iv.stream().mapToDouble(x -> Math.pow(x - mean, 2)).average().orElse(0);
        double sd = Math.sqrt(variance);
        double cv = sd / mean;

        // Sinal 1: variância baixa demais para ser humano
        double cvThreshold = core.getConfig().getDouble("macro-protection.cv-threshold", 0.15);
        double sigLowVariance = cv < cvThreshold ? clamp(1 - (cv / Math.max(1e-9, cvThreshold))) : 0;

        // Sinal 2: mesmo intervalo repetido demais
        long tol = Math.max(1, core.getConfig().getLong("macro-protection.repetition-tolerance-ms", 15));
        Map<Long, Integer> repBuckets = new HashMap<>();
        for (long v : iv) repBuckets.merge(Math.round(v / (double) tol), 1, Integer::sum);
        int modeCount = repBuckets.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        double repetitionRatio = (double) modeCount / iv.size();
        double repThreshold = core.getConfig().getDouble("macro-protection.repetition-ratio-threshold", 0.4);
        double sigRepetition = repetitionRatio > repThreshold ? clamp((repetitionRatio - repThreshold) / (1 - repThreshold)) : 0;

        // Sinal 3: ciclagem entre poucos valores discretos (pega delay "aleatorizado" em loop fechado)
        long cbucket = Math.max(1, core.getConfig().getLong("macro-protection.cycle-bucket-ms", 50));
        Map<Long, Integer> cyc = new HashMap<>();
        for (long v : iv) cyc.merge(Math.round(v / (double) cbucket), 1, Integer::sum);
        int maxBuckets = core.getConfig().getInt("macro-protection.cycle-max-buckets", 3);
        List<Integer> counts = new ArrayList<>(cyc.values());
        counts.sort(Collections.reverseOrder());
        int topSum = 0;
        for (int i = 0; i < Math.min(maxBuckets, counts.size()); i++) topSum += counts.get(i);
        double coverage = (double) topSum / iv.size();
        double covThreshold = core.getConfig().getDouble("macro-protection.cycle-coverage-threshold", 0.85);
        double sigCycling = (cyc.size() <= maxBuckets * 2 && coverage > covThreshold)
                ? clamp((coverage - covThreshold) / (1 - covThreshold)) : 0;

        // Sinal 4: alinhamento a múltiplos redondos de 100ms (típico de timers de script)
        long rtol = core.getConfig().getLong("macro-protection.round-tick-tolerance-ms", 6);
        int roundHits = 0;
        for (long v : iv) { long rem = v % 100; if (rem <= rtol || rem >= 100 - rtol) roundHits++; }
        double roundRatio = (double) roundHits / iv.size();
        double rrThreshold = core.getConfig().getDouble("macro-protection.round-tick-ratio-threshold", 0.5);
        double sigRoundTick = roundRatio > rrThreshold ? clamp((roundRatio - rrThreshold) / (1 - rrThreshold)) : 0;

        double wLow = core.getConfig().getDouble("macro-protection.weights.low-variance", 0.35);
        double wRep = core.getConfig().getDouble("macro-protection.weights.repetition", 0.25);
        double wCyc = core.getConfig().getDouble("macro-protection.weights.cycling", 0.25);
        double wRnd = core.getConfig().getDouble("macro-protection.weights.round-tick", 0.15);

        double confidence = sigLowVariance * wLow + sigRepetition * wRep + sigCycling * wCyc + sigRoundTick * wRnd;
        double threshold = core.getConfig().getDouble("macro-protection.confidence-threshold", 0.65);

        if (confidence >= threshold) {
            int need = Math.max(1, core.getConfig().getInt("macro-protection.required-consecutive-flags", 2));
            int s = streak.merge(key, 1, Integer::sum);
            if (s < need) return; // exige padrão sustentado em janelas consecutivas, não um pico isolado

            long cooldownMs = core.getConfig().getLong("macro-protection.cooldown-seconds", 120) * 1000L;
            long now = System.currentTimeMillis();
            long last = lastAlert.getOrDefault(key, 0L);
            if (now - last < cooldownMs) return;
            lastAlert.put(key, now);
            streak.put(key, 0);

            List<String> reasons = new ArrayList<>();
            if (sigLowVariance > 0) reasons.add("variância baixa (cv=" + String.format(Locale.US, "%.3f", cv) + ")");
            if (sigRepetition > 0) reasons.add("intervalo repetido (" + Math.round(repetitionRatio * 100) + "%)");
            if (sigCycling > 0) reasons.add("ciclagem entre " + cyc.size() + " valores (" + Math.round(coverage * 100) + "%)");
            if (sigRoundTick > 0) reasons.add("alinhado a tick redondo (" + Math.round(roundRatio * 100) + "%)");

            core.macroAlert(p, action, confidence, String.join(", ", reasons));
        } else {
            streak.put(key, 0);
        }
    }

    private static double clamp(double x) { return Math.max(0, Math.min(1, x)); }

    /** Limpa o histórico do jogador (chamado no quit para não vazar memória). */
    public void forget(Player p) {
        String prefix = p.getUniqueId() + "|";
        history.keySet().removeIf(k -> k.startsWith(prefix));
        sinceEval.keySet().removeIf(k -> k.startsWith(prefix));
        streak.keySet().removeIf(k -> k.startsWith(prefix));
        lastAlert.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
