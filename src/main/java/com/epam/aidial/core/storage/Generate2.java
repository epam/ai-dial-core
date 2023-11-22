package com.epam.aidial.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Generate2 {
    public static void main(String[] args) throws IOException {
        Map<String, List<Integer>> frequencies = new HashMap<>();
        try (var in = Files.newBufferedReader(Path.of("c:\\_Projects\\ai-dial\\core-github\\chats.csv"))) {
            String s = in.readLine();
            while (s != null) {
                String[] split = s.split(",");
                frequencies.computeIfAbsent(split[1], (k) -> new ArrayList<>())
                                .add(Integer.parseInt(split[2]));
                s = in.readLine();
            }
        }

        List<Double> probs = new ArrayList<>(5);

        for (var pair : frequencies.entrySet()) {
            var list = pair.getValue();
            int sum = list.stream().mapToInt(Integer::intValue).sum();
            list.sort(Comparator.reverseOrder());

            for (int i = 0; i < list.size(); ++i) {
                if (probs.size() <= i) {
                    probs.add(0.0);
                }
                probs.set(i, probs.get(i) + list.get(i) / (double) sum);
            }
        }

        double sum = probs.stream().mapToDouble(Double::doubleValue).sum();

        try (var out = Files.newBufferedWriter(Path.of("chatHist.csv"))) {
            for (int i = 0; i < probs.size(); ++i) {
                probs.set(i, probs.get(i) / sum);
                out.write(probs.get(i) + "\n");
            }
        }

        try (var out = Files.newBufferedWriter(Path.of("chatHist2.csv"))) {
            int[] hist = new int[frequencies.values().stream().mapToInt(List::size).max().getAsInt() + 1];
            for (var list : frequencies.values()) {
                ++hist[list.size()];
            }
            for (int i = 0; i < hist.length; ++i) {
                out.write(hist[i] + "\n");
            }
        }

        double factor = Generate.solve(1.0, 0.5, x -> Generate.sqError(probs.stream().mapToDouble(Double::doubleValue).toArray(), x));
        System.out.println(factor);
    }
}
