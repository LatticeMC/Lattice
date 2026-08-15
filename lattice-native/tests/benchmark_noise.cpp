#include <algorithm>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <limits>
#include <string>
#include <string_view>
#include <vector>

#include "lattice/dispatch.hpp"
#include "world/gen/noise/double_perlin_noise.hpp"
#include "world/gen/noise/interpolated_noise.hpp"
#include "world/gen/noise/octave_perlin_noise.hpp"
#include "world/gen/noise/perlin_noise.hpp"
#include "world/gen/noise/simplex_noise.hpp"

namespace noise = lattice::world::gen::noise;

namespace {

using Clock = std::chrono::steady_clock;

struct Options {
    std::string tier = "auto";
    int warmup = 5;
    int samples = 15;
    std::size_t target_points = 200000;
    std::size_t heavy_target_points = 10000;
    std::vector<std::size_t> counts{1, 4, 8, 16, 49, 128, 129, 245, 256, 2048, 24576};
};

struct Stats {
    double p50_ns_per_point;
    double p95_ns_per_point;
    double checksum;
};

struct OctaveBundle {
    std::vector<noise::PerlinNoiseSampler> octaves;
    std::vector<double> amplitudes;
    noise::OctavePerlinNoiseSampler sampler{};

    void refresh() noexcept {
        sampler.octaves = octaves.data();
        sampler.amplitudes = amplitudes.data();
        sampler.octave_count = octaves.size();
    }
};

volatile double g_sink = 0.0;

std::size_t parse_size(std::string_view value, std::size_t fallback) {
    std::string text(value);
    char* end = nullptr;
    const unsigned long long parsed = std::strtoull(text.c_str(), &end, 10);
    return end != text.c_str() && *end == '\0' ? static_cast<std::size_t>(parsed) : fallback;
}

std::vector<std::size_t> parse_counts(std::string_view value) {
    std::vector<std::size_t> result;
    std::size_t begin = 0;
    while (begin <= value.size()) {
        const std::size_t end = value.find(',', begin);
        const std::string_view token = value.substr(begin, end == std::string_view::npos ? value.size() - begin : end - begin);
        const std::size_t count = parse_size(token, 0);
        if (count != 0) result.push_back(count);
        if (end == std::string_view::npos) break;
        begin = end + 1;
    }
    if (result.empty()) result.push_back(1);
    return result;
}

Options parse_options(int argc, char** argv) {
    Options options;
    for (int i = 1; i < argc; ++i) {
        const std::string_view argument(argv[i]);
        if (argument.starts_with("--tier=")) {
            options.tier = std::string(argument.substr(7));
        } else if (argument.starts_with("--warmup=")) {
            options.warmup = static_cast<int>(parse_size(argument.substr(9), options.warmup));
        } else if (argument.starts_with("--samples=")) {
            options.samples = static_cast<int>(parse_size(argument.substr(10), options.samples));
        } else if (argument.starts_with("--target-points=")) {
            options.target_points = parse_size(argument.substr(16), options.target_points);
        } else if (argument.starts_with("--heavy-target-points=")) {
            options.heavy_target_points = parse_size(argument.substr(22), options.heavy_target_points);
        } else if (argument.starts_with("--counts=")) {
            options.counts = parse_counts(argument.substr(9));
        } else {
            std::cerr << "unknown argument: " << argument << '\n';
            std::exit(2);
        }
    }
    options.warmup = std::max(options.warmup, 0);
    options.samples = std::max(options.samples, 1);
    options.target_points = std::max<std::size_t>(options.target_points, 1);
    options.heavy_target_points = std::max<std::size_t>(options.heavy_target_points, 1);
    return options;
}

noise::PerlinNoiseSampler make_perlin(std::uint8_t seed, double origin) {
    noise::PerlinNoiseSampler sampler{};
    sampler.origin_x = origin;
    sampler.origin_y = origin * -0.75;
    sampler.origin_z = origin * 1.25;
    for (int i = 0; i < 256; ++i) {
        sampler.permutation[i] = static_cast<std::uint8_t>(((i * 31) ^ (seed + i * 7)) & 0xFF);
    }
    return sampler;
}

OctaveBundle make_octaves(std::size_t count, std::uint8_t seed) {
    OctaveBundle result;
    result.octaves.reserve(count);
    result.amplitudes.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
        result.octaves.push_back(make_perlin(static_cast<std::uint8_t>(seed + i * 13), 0.25 + static_cast<double>(i) * 0.5));
        result.amplitudes.push_back(i % 5 == 3 ? 0.0 : 1.0);
    }
    result.sampler.lacunarity = 0.5;
    result.sampler.persistence = 1.0;
    result.refresh();
    return result;
}

noise::SimplexNoiseSampler make_simplex() {
    noise::SimplexNoiseSampler sampler{};
    sampler.origin_x = 1.25;
    sampler.origin_y = -3.5;
    sampler.origin_z = 7.75;
    for (int i = 0; i < 256; ++i) sampler.permutation[i] = ((i * 31) ^ 0x5A) & 0xFF;
    return sampler;
}

void fill_coordinates(std::size_t count, std::vector<double>& x, std::vector<double>& y, std::vector<double>& z) {
    x.resize(count);
    y.resize(count);
    z.resize(count);
    for (std::size_t i = 0; i < count; ++i) {
        const double value = static_cast<double>(i);
        x[i] = -8192.25 + value * 0.371;
        y[i] = -64.5 + value * 0.413;
        z[i] = 4096.75 - value * 0.437;
    }
}

template <typename Function>
Stats measure(Function&& function, std::vector<double>& output, std::size_t count,
              int warmup, int samples, std::size_t target_points) {
    const std::size_t iterations = std::max<std::size_t>(1, target_points / count);
    for (int i = 0; i < warmup; ++i) {
        for (std::size_t iteration = 0; iteration < iterations; ++iteration) function();
        g_sink = g_sink + output[(static_cast<std::size_t>(i) * 17) % count];
    }

    std::vector<double> timings;
    timings.reserve(static_cast<std::size_t>(samples));
    double checksum = 0.0;
    for (int sample = 0; sample < samples; ++sample) {
        const auto start = Clock::now();
        for (std::size_t iteration = 0; iteration < iterations; ++iteration) function();
        const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count();
        timings.push_back(static_cast<double>(elapsed) / static_cast<double>(iterations * count));
        checksum += output[(static_cast<std::size_t>(sample) * 31) % count];
    }
    std::sort(timings.begin(), timings.end());
    const std::size_t p50_index = timings.size() / 2;
    const std::size_t p95_index = std::min(timings.size() - 1,
        static_cast<std::size_t>(std::ceil(static_cast<double>(timings.size()) * 0.95)) - 1);
    g_sink = g_sink + checksum;
    return Stats{timings[p50_index], timings[p95_index], checksum};
}

struct Parity {
    bool bitwise = true;
    double max_abs_error = 0.0;
};

Parity compare(const std::vector<double>& expected, const std::vector<double>& actual) {
    Parity parity;
    for (std::size_t i = 0; i < expected.size(); ++i) {
        parity.bitwise &= std::bit_cast<std::uint64_t>(expected[i]) == std::bit_cast<std::uint64_t>(actual[i]);
        parity.max_abs_error = std::max(parity.max_abs_error, std::abs(expected[i] - actual[i]));
    }
    return parity;
}

std::string expected_path(std::string_view algorithm, std::string_view operation,
                          std::size_t count, const lattice::cpu::Features& features) {
    const bool avx512 = features.requested_tier == lattice::cpu::RequestedTier::Avx512
        && features.avx512f && features.avx512dq && features.avx512vl && count >= 129;
    if ((algorithm == "perlin" || algorithm == "double-perlin") && avx512) return "avx512";
    if (algorithm == "interpolated" && operation == "batch") return "scalar";
    if (algorithm == "octave" || algorithm == "interpolated") {
        if (avx512) return "scalar-orchestration+perlin-avx512";
        if (features.avx2) return "scalar-orchestration+perlin-avx2";
        return "scalar";
    }
    if (features.avx2) return "avx2";
    return "scalar";
}

void print_result(std::string_view algorithm, std::string_view operation,
                  const Options& options, const lattice::cpu::Features& features,
                  std::size_t count, const Stats& stats, const Parity& parity) {
    const double points_per_second = 1.0e9 / stats.p50_ns_per_point;
    std::cout << algorithm << ',' << operation << ',' << options.tier << ','
              << expected_path(algorithm, operation, count, features) << ','
              << count << ',' << options.warmup << ',' << options.samples << ','
              << std::fixed << std::setprecision(3)
              << stats.p50_ns_per_point << ',' << stats.p95_ns_per_point << ','
              << points_per_second << ',' << std::setprecision(17) << stats.checksum << ','
              << (parity.bitwise ? "yes" : "no") << ',' << std::scientific << parity.max_abs_error << '\n';
}

template <typename Batch, typename Point>
bool run_batch_case(std::string_view algorithm, std::string_view operation,
                    const Options& options, const lattice::cpu::Features& features,
                    std::size_t count, std::size_t target_points,
                    Batch&& batch, Point&& point) {
    std::vector<double> x, y, z, output(count), expected(count);
    fill_coordinates(count, x, y, z);
    for (std::size_t i = 0; i < count; ++i) expected[i] = point(x[i], y[i], z[i]);
    batch(x, y, z, output);
    const Parity parity = compare(expected, output);
    const Stats stats = measure([&] { batch(x, y, z, output); }, output, count,
                                options.warmup, options.samples, target_points);
    print_result(algorithm, operation, options, features, count, stats, parity);
    return parity.max_abs_error <= 1.0e-12;
}

template <typename Column, typename Point>
bool run_column_case(std::string_view algorithm, const Options& options,
                     const lattice::cpu::Features& features, std::size_t count,
                     std::size_t target_points, Column&& column, Point&& point) {
    constexpr double x = 37.25;
    constexpr double y0 = -23.5;
    constexpr double z = 91.75;
    constexpr double dy = 4.125;
    std::vector<double> output(count), expected(count);
    for (std::size_t i = 0; i < count; ++i) expected[i] = point(x, y0 + static_cast<double>(i) * dy, z);
    column(x, y0, z, dy, output);
    const Parity parity = compare(expected, output);
    const Stats stats = measure([&] { column(x, y0, z, dy, output); }, output, count,
                                options.warmup, options.samples, target_points);
    print_result(algorithm, "y-column", options, features, count, stats, parity);
    return parity.max_abs_error <= 1.0e-12;
}

} // namespace

int main(int argc, char** argv) {
    const Options options = parse_options(argc, argv);
    if (!lattice::cpu::configure_requested_tier(options.tier.c_str())) {
        std::cerr << "invalid or late requested tier: " << options.tier << '\n';
        return 2;
    }
    const lattice::cpu::Features& features = lattice::cpu::initialize();
    std::cout << "cpu," << lattice::cpu::summary() << '\n';
    std::cout << "algorithm,operation,requested-tier,expected-path,count,warmup,samples,p50-ns/point,p95-ns/point,points/s,checksum,bitwise,max-abs-error\n";

    const noise::PerlinNoiseSampler perlin = make_perlin(0x47, 12.5);
    OctaveBundle octave = make_octaves(8, 0x31);
    OctaveBundle first = make_octaves(4, 0x11);
    OctaveBundle second = make_octaves(4, 0x71);
    octave.refresh();
    first.refresh();
    second.refresh();
    noise::DoublePerlinNoiseSampler double_perlin{first.sampler, second.sampler, noise::create_amplitude(4)};

    OctaveBundle lower = make_octaves(16, 0x21);
    OctaveBundle upper = make_octaves(16, 0x51);
    OctaveBundle interpolation = make_octaves(8, 0x91);
    lower.refresh();
    upper.refresh();
    interpolation.refresh();
    noise::InterpolatedNoiseSampler interpolated{
        &lower.sampler, &upper.sampler, &interpolation.sampler,
        1.0, 1.0, 80.0, 160.0, 8.0
    };
    const noise::SimplexNoiseSampler simplex = make_simplex();

    bool parity_ok = true;
    for (const std::size_t count : options.counts) {
        parity_ok &= run_batch_case("perlin", "batch", options, features, count, options.target_points,
            [&](const auto& x, const auto& y, const auto& z, auto& out) {
                noise::sample_batch(perlin, x.data(), y.data(), z.data(), count, out.data());
            },
            [&](double x, double y, double z) { return noise::sample(perlin, x, y, z); });
        parity_ok &= run_column_case("perlin", options, features, count, options.target_points,
            [&](double x, double y0, double z, double dy, auto& out) {
                noise::sample_y_column(perlin, x, y0, z, dy, count, out.data());
            },
            [&](double x, double y, double z) { return noise::sample(perlin, x, y, z); });

        parity_ok &= run_batch_case("octave", "batch", options, features, count, options.target_points,
            [&](const auto& x, const auto& y, const auto& z, auto& out) {
                noise::sample_batch(octave.sampler, x.data(), y.data(), z.data(), count, out.data());
            },
            [&](double x, double y, double z) { return noise::sample(octave.sampler, x, y, z); });
        parity_ok &= run_column_case("octave", options, features, count, options.target_points,
            [&](double x, double y0, double z, double dy, auto& out) {
                noise::sample_y_column(octave.sampler, x, y0, z, dy, count, out.data());
            },
            [&](double x, double y, double z) { return noise::sample(octave.sampler, x, y, z); });

        parity_ok &= run_batch_case("double-perlin", "batch", options, features, count, options.target_points,
            [&](const auto& x, const auto& y, const auto& z, auto& out) {
                noise::sample_batch(double_perlin, x.data(), y.data(), z.data(), count, out.data());
            },
            [&](double x, double y, double z) { return noise::sample(double_perlin, x, y, z); });
        parity_ok &= run_column_case("double-perlin", options, features, count, options.target_points,
            [&](double x, double y0, double z, double dy, auto& out) {
                noise::sample_y_column(double_perlin, x, y0, z, dy, count, out.data());
            },
            [&](double x, double y, double z) { return noise::sample(double_perlin, x, y, z); });

        parity_ok &= run_batch_case("interpolated", "batch", options, features, count, options.heavy_target_points,
            [&](const auto& x, const auto& y, const auto& z, auto& out) {
                noise::sample_batch(interpolated, x.data(), y.data(), z.data(), count, out.data());
            },
            [&](double x, double y, double z) { return noise::sample(interpolated, x, y, z); });
        parity_ok &= run_column_case("interpolated", options, features, count, options.heavy_target_points,
            [&](double x, double y0, double z, double dy, auto& out) {
                noise::sample_y_column(interpolated, x, y0, z, dy, count, out.data());
            },
            [&](double x, double y, double z) { return noise::sample(interpolated, x, y, z); });

        parity_ok &= run_batch_case("simplex-2d", "batch", options, features, count, options.target_points,
            [&](const auto& x, const auto& y, const auto&, auto& out) {
                noise::sample_2d_batch(simplex, x.data(), y.data(), count, out.data());
            },
            [&](double x, double y, double) { return noise::sample_2d(simplex, x, y); });
        parity_ok &= run_batch_case("simplex-3d", "batch", options, features, count, options.target_points,
            [&](const auto& x, const auto& y, const auto& z, auto& out) {
                noise::sample_3d_batch(simplex, x.data(), y.data(), z.data(), count, out.data());
            },
            [&](double x, double y, double z) { return noise::sample_3d(simplex, x, y, z); });
    }

    std::cout << "result," << (parity_ok ? "success" : "parity-failure") << ",sink=" << std::setprecision(17) << g_sink << '\n';
    return parity_ok ? 0 : 1;
}
