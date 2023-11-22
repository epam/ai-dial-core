package com.epam.aidial.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.math3.distribution.ZipfDistribution;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.commons.math3.util.FastMath;

public class Generate {

    private static double generalizedHarmonic(final int n, final double exponent) {
        double value = 0;
        for (int k = n; k > 0; --k) {
            value += 1.0 / FastMath.pow(k, exponent);
        }
        return value;
    }

    public static double probability(final int x, int n, double exponent, double generalizedHarm) {
        return 1.0 / (FastMath.pow(x, exponent) * generalizedHarm);
    }


//    public double logLikelyHood(double factor) {
//        ZipfDistribution zipf = new ZipfDistribution(samples.length, factor);
//        double llm = 0;
//        for (int i = 0; i < samples.length; ++i) {
//            llm += Math.log(zipf.probability(i)) * samples[i]ж
//        }
//        return llm;
//    }


    public static double estimate(int[] frequencies) {
        Arrays.sort(frequencies);

        double n = frequencies.length;
        double hN = 0.0;
        for (double i = 1.0; i <= n; i++) {
            hN += 1.0 / i;
        }

        double meanLogF = 0.0;
        for (double f : frequencies) {
            meanLogF += Math.log(f);
        }
        meanLogF /= n;

        double sDenominator = 0.0;
        for (int i = 1; i <= n; i++) {
            sDenominator += (Math.log(frequencies[(int) i - 1]) - meanLogF) * i;
        }

        return 1 + (n / sDenominator) * hN;
    }

    public static double estimate2(int[] frequencies) {
        Arrays.sort(frequencies);
        for (int i = 0; i < frequencies.length >> 1; ++i) {
            int t = frequencies[frequencies.length - i - 1];
            frequencies[frequencies.length - i - 1] = frequencies[i];
            frequencies[i] = t;
        }
        double sum = Arrays.stream(frequencies).sum();
        double[] prob = new double[frequencies.length];
        for (int i = 0; i < frequencies.length; ++i) {
            prob[i] = frequencies[i] / sum;
        }

        SimpleRegression regression = new SimpleRegression();
        for (int i = 1; i <= frequencies.length; i++) {
            regression.addData(Math.log(i), Math.log(prob[i - 1]));
        }

        return -regression.getSlope();
    }

    public static double sqError(double[] probs, double s) {
        double sq = 0.0;
        double generalizedHarm = generalizedHarmonic(probs.length, s);
        for (int i = 1; i <= probs.length; ++i) {
            double er = probability(i, probs.length, s, generalizedHarm) - probs[i - 1];
            sq += er * er;
        }
        return sq;
    }

    public interface D2DFunc {
        double apply(double x);
    }

    public static double solve(double start, double startStep, D2DFunc objective) {

        double step = startStep;
        double x = start;
        while (step > 1e-8) {
            double left = objective.apply(x - step);
            double current = objective.apply(x);
            double right = objective.apply(x + step);

            if (left < current && left < right) {
                x = x - step;
            } else if (right < current && right < left) {
                x = x + step;
            }
            step /= 2;
        }

        return x;
    }

    public static double estimate3(int[] frequencies) {
        Arrays.sort(frequencies);
        for (int i = 0; i < frequencies.length >> 1; ++i) {
            int t = frequencies[frequencies.length - i - 1];
            frequencies[frequencies.length - i - 1] = frequencies[i];
            frequencies[i] = t;
        }
        double sum = Arrays.stream(frequencies).sum();
        double[] prob = new double[frequencies.length];
        for (int i = 0; i < frequencies.length; ++i) {
            prob[i] = frequencies[i] / sum;
        }

        return solve(1.0, 0.5, x -> sqError(prob, x));
    }

    public static void main(String[] args) throws IOException {

        List<Integer> frequencies = new ArrayList<>();
        try (var in = Files.newBufferedReader(Path.of("c:\\_Projects\\ai-dial\\core-github\\frequencies.csv"))) {
            String s = in.readLine();
            while (s != null) {
                frequencies.add(Integer.parseInt(s));
                s = in.readLine();
            }
        }

        //double exponent = estimate3(frequencies.stream().mapToInt(Integer::intValue).toArray());
        int samplesCount = frequencies.stream().mapToInt(Integer::intValue).sum();
        System.out.println("samplesCount: " + samplesCount);
        double exponent = 0.5573040992021561;
        System.out.println(exponent);


        //System.exit(0);
        int numOfOptions = 50000;
        int numOfSamples = (int) (numOfOptions * (samplesCount / (double) frequencies.size()));  // The number of elements to choose from
        System.out.println("New numOfSamples: " +  numOfSamples);
//        int numOfElements = frequencies.stream().mapToInt(Integer::intValue).sum();
//        int numOfOptions = frequencies.size();

        ZipfDistribution zipf = new ZipfDistribution(numOfOptions, exponent);

        int[] pdf = new int[numOfOptions];

        for (int i = 0; i < numOfSamples; ++i) {
            ++pdf[zipf.sample() - 1];
        }

//        System.out.print("[" + pdf[0]);
//        for (int i = 1; i < numOfElements; ++i) {
//            System.out.print(", " + pdf[i]);
//        }
//
//        System.out.println("]");
        try (var out = Files.newBufferedWriter(Path.of("hist.csv"))) {
            for (int i : pdf) {
                out.write(i + "\n");
            }
        }
    }
}