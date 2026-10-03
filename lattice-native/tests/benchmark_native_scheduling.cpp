// 手工隔离基准。输入生成、parity 和分布统计不进入计时。
#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <numeric>
#include <random>
#include <string>
#include <vector>
#include "entity_query_reference.hpp"
#include "scheduling_reference.hpp"
#include "cache_state_test_access.hpp"
#include "heightmap_scheduling_reference.hpp"
#include "world/entity/pathfinder.hpp"
#include "world/entity/los.hpp"

namespace df = lattice::world::gen::densityfunction;
namespace cn = lattice::world::gen::chunknoise;
namespace ent = lattice::world::entity;
namespace hm = lattice::world::heightmap;
using Clock = std::chrono::steady_clock;
volatile double sink = 0;
void require(bool ok, const char* message) {
    if (!ok) { std::fprintf(stderr, "parity failed: %s\n", message); std::exit(2); }
}
template<class Fn> double time_calls(Fn& fn, int count) {
    const auto start = Clock::now();
    double sum = 0;
    for (int i = 0; i < count; ++i) {
#if defined(__GNUC__) || defined(__clang__)
        asm volatile("" : : "g"(&fn) : "memory");
#endif
        sum += fn();
#if defined(__GNUC__) || defined(__clang__)
        asm volatile("" : : "g"(&fn) : "memory");
#endif
    }
    sink = sum;
    return std::chrono::duration<double, std::nano>(Clock::now() - start).count() / count;
}
template<class A, class B> void compare(const std::string& name, A a, B b) {
    require(a() == b(), name.c_str());
    int count = 1;
    while (count < 100000 && time_calls(a, count) * count < 1000000) count *= 2;
    for (int i = 0; i < 4; ++i) { time_calls(a, count); time_calls(b, count); }
    std::vector<double> av, bv, ratios;
    for (int i = 0; i < 21; ++i) {
        double x, y;
        if (i % 2) { y = time_calls(b, count); x = time_calls(a, count); }
        else { x = time_calls(a, count); y = time_calls(b, count); }
        av.push_back(x); bv.push_back(y); ratios.push_back(x / y);
    }
    for (auto* v : {&av, &bv, &ratios}) std::sort(v->begin(), v->end());
    std::printf("AB,%s,%d,%.3f,%.3f,%.5f,%.5f,%.5f\n", name.c_str(), count, av[10], bv[10], ratios[10], ratios[2], ratios[18]);
}
void entity_bench() {
    std::mt19937 rng(42);
    for (int n : {16, 128, 1024}) for (int k : {1, 4, n}) for (int shape : {0, 1, 2}) {
        std::vector<int> ids(n), types(n, 1), a(n), b(n);
        std::vector<double> pos(n*3), boxes(n*6, 0), da(n*2), db(n*2);
        std::vector<std::uint8_t> alive(n, 1), spectator(n, 0);
        std::iota(ids.begin(), ids.end(), 0);
        for (int i = 0; i < n; ++i) pos[i*3] = shape == 0 ? rng() % 1024 : shape == 1 ? i : n - i;
        ent::EntityQueryInputs in{};
        in.entity_ids = ids.data(); in.entity_type_ids = types.data(); in.entity_positions = pos.data();
        in.entity_aabbs = boxes.data(); in.entity_alive = alive.data(); in.entity_spectator = spectator.data();
        in.entity_count = n; in.sort_by_distance = true; in.max_results = k;
        const auto old = [&] { auto count = entity_query_reference::query_entities(in, a.data(), da.data(), n); return double(count + a[0] + a[count-1]); };
        const auto candidate = [&] { auto count = ent::query_entities(in, b.data(), db.data(), n); return double(count + b[0] + b[count-1]); };
        old(); candidate();
        for (int i = 0; i < k; ++i) require(a[i] == b[i] && da[i] == db[i] && da[n+i] == db[n+i], "entity order");
        compare("entity/n="+std::to_string(n)+"/k="+std::to_string(k)+"/shape="+std::to_string(shape), old, candidate);
    }
}
namespace entity_query_predicate_candidate {
std::size_t query_entities(const ent::EntityQueryInputs&,int*,double*,std::size_t) noexcept;
}
void predicate_bench() {
    for (int n : {16,128,1024}) for (int predicate=0;predicate<5;++predicate) {
        std::vector<int> ids(n),types(n,1),a(n),b(n);std::iota(ids.begin(),ids.end(),0);
        std::vector<double> pos(n*3),boxes(n*6),da(n*2),db(n*2);
        std::vector<std::uint8_t> alive(n,1),spectator(n,0);
        for(int i=0;i<n;++i){pos[i*3]=(i*17)%23;alive[i]=i%4!=0;spectator[i]=i%7==0;}
        ent::EntityQueryInputs in{};in.entity_ids=ids.data();in.entity_type_ids=types.data();in.entity_positions=pos.data();
        in.entity_aabbs=boxes.data();in.entity_alive=alive.data();in.entity_spectator=spectator.data();in.entity_count=n;
        in.sort_by_distance=true;in.max_results=n;in.predicate_kind=static_cast<ent::EntityPredicateKind>(predicate);in.excluded_entity_id=7;
        auto old=[&]{auto c=ent::query_entities(in,a.data(),da.data(),n);return double(c+a[0]);};
        auto candidate=[&]{auto c=entity_query_predicate_candidate::query_entities(in,b.data(),db.data(),n);return double(c+b[0]);};
        auto count=entity_query_predicate_candidate::query_entities(in,b.data(),db.data(),n);old();
        for(std::size_t i=0;i<count;++i)require(a[i]==b[i]&&da[i]==db[i]&&da[n+i]==db[n+i],"predicate order");
        compare("predicate/n="+std::to_string(n)+"/kind="+std::to_string(predicate),old,candidate);
    }
}
namespace entity_query_allowed_candidate {
std::size_t query_entities(const ent::EntityQueryInputs&,int*,double*,std::size_t) noexcept;
}
void allowed_bench() {
    for (int n : {16,128,1024}) for (int m : {8,64,512}) {
        std::vector<int> ids(n),types(n),allowed(m),a(n),b(n);std::iota(ids.begin(),ids.end(),0);
        for(int j=0;j<m;++j)allowed[j]=(j*17)%m-64;
        for(int i=0;i<n;++i)types[i]=i%128-64;
        const auto saved=allowed;
        std::vector<double> pos(n*3),boxes(n*6),da(n*2),db(n*2);std::vector<std::uint8_t> alive(n,1),spectator(n,0);
        for(int i=0;i<n;++i)pos[i*3]=i;
        ent::EntityQueryInputs in{};in.entity_ids=ids.data();in.entity_type_ids=types.data();in.entity_positions=pos.data();
        in.entity_aabbs=boxes.data();in.entity_alive=alive.data();in.entity_spectator=spectator.data();in.entity_count=n;
        in.sort_by_distance=true;in.max_results=n;in.allowed_type_ids=allowed.data();in.allowed_type_count=m;
        auto old=[&]{auto c=ent::query_entities(in,a.data(),da.data(),n);return double(c+a[0]);};
        auto candidate=[&]{auto c=entity_query_allowed_candidate::query_entities(in,b.data(),db.data(),n);return double(c+b[0]);};
        auto count=entity_query_allowed_candidate::query_entities(in,b.data(),db.data(),n);old();
        require(saved==allowed,"allowed input immutable");
        for(std::size_t i=0;i<count;++i)require(a[i]==b[i]&&da[i]==db[i]&&da[n+i]==db[n+i],"allowed order");
        compare("allowed/n="+std::to_string(n)+"/m="+std::to_string(m),old,candidate);
    }
}
void cache_bench() {
    for (int slots : {16, 256, 4096}) for (int percent : {0, 1, 100}) {
        df::NodeArena arena; arena.num_cache_2d_slots = arena.num_cache_once_slots = arena.num_flat_cache_slots = slots;
        arena.num_shared_leaf_slots = arena.num_cache_all_in_cell_slots = slots;
        df::CacheState a, b; a.resize_for(arena); b.resize_for(arena);
        const int used = slots * percent / 100;
        auto visit = [&](df::CacheState& c) {
            double sum = 0;
            for (int i = 0; i < used; ++i) {
                auto& e = c.cache_once_at(i);
                if (!e.valid) e = {true, 1, 2, 3, double(i)};
                sum += e.value;
                c.cache_all_in_cell_at(i).get_or_insert(i) = i;
                c.bind_cell_array(i, &e.value, 1);
            }
            return sum;
        };
        compare("cache/eager-lazy/slots="+std::to_string(slots)+"/percent="+std::to_string(percent),
            [&]{ df::CacheStateTestAccess::eager_clear(a); return visit(a); }, [&]{ b.clear(); return visit(b); });
    }
    // 旧裸读取与新 accessor 的热命中成本，不冒充整个 evaluator A/B。
    for (int slots : {16, 256, 4096}) {
        std::vector<df::CacheOnceEntry> old(slots);
        df::NodeArena arena; arena.num_cache_once_slots = slots;
        df::CacheState candidate; candidate.resize_for(arena);
        for (int i=0;i<slots;++i) { old[i] = {true,1,2,3,double(i)}; candidate.cache_once_at(i)=old[i]; }
        compare("cache/warm-read/slots="+std::to_string(slots), [&]{
            double sum=0; for(auto& e:old) if(e.valid && e.y==2) sum+=e.value; return sum;
        }, [&]{ double sum=0; for(int i=0;i<slots;++i) { auto& e=candidate.cache_once_at(i); if(e.valid && e.y==2) sum+=e.value; } return sum; });
    }
}
void column_bench() {
    for (int slots : {1, 16}) for (int height : {1, 48}) {
        df::NodeArena arena;
        df::Node grad{}; grad.kind=df::NodeKind::kYClampedGradient; grad.i0=-64; grad.i1=320; grad.d0=-1;grad.d1=1;
        auto root=arena.push(grad);
        for(int i=0;i<slots;++i) { df::Node node{};node.kind=df::NodeKind::kInterpolated;node.a=root;arena.root=arena.push(node); }
        cn::ChunkNoiseSampler sampler;sampler.router.final_density=&arena;sampler.prepare_cache();sampler.prepare_interpolators(cn::Channel::kFinalDensity,4,height);
        cn::ChunkNoiseSampler old_sampler;old_sampler.router.final_density=&arena;old_sampler.prepare_cache();old_sampler.prepare_interpolators(cn::Channel::kFinalDensity,4,height);
        auto& old=old_sampler.caches[static_cast<std::size_t>(cn::Channel::kFinalDensity)];
        auto& candidate=sampler.caches[static_cast<std::size_t>(cn::Channel::kFinalDensity)];
        auto a=[&]{ scheduling_reference::fill_start_density_column(old_sampler,cn::Channel::kFinalDensity,0,0,0,0,-64,8,4,height);return old.interpolators.back().start_density_buffer.back(); };
        auto b=[&]{ sampler.fill_start_density_column(cn::Channel::kFinalDensity,0,0,0,0,-64,8,4,height);return candidate.interpolators.back().start_density_buffer.back(); };
        a();b();for(int i=0;i<slots;++i) require(old.interpolators[i].start_density_buffer==candidate.interpolators[i].start_density_buffer,"column");
        compare("column/slots="+std::to_string(slots)+"/height="+std::to_string(height),a,b);
    }
}
void path_bench() {
    for (int n : {256, 4096, 65536}) {
        std::vector<std::int8_t> types(n,2);float malus[]={-1,0,0};
        std::vector<std::uint64_t> a,b,c,d;auto words=(n+63)/64;
        auto old=[&]{a.assign(words,0);b.assign(words,0);ent::build_pathfinder_masks(types.data(),n,malus,3,{a.data(),b.data()});return double(a.back()+b.back());};
        auto candidate=[&]{c.resize(words);d.resize(words);ent::build_pathfinder_masks(types.data(),n,malus,3,{c.data(),d.data()});return double(c.back()+d.back());};
        old();candidate();require(a==c&&b==d,"path masks");
        compare("path/masks/n="+std::to_string(n),old,candidate);
    }
    std::vector<std::int8_t> types(64*3*16,1);std::fill_n(types.begin(),64*16,2);float malus[]={-1,0,0};
    int tx=63,ty=0,tz=8;ent::PathfinderInputs in{};
    in.path_types=types.data();in.region_size_x=64;in.region_size_y=3;in.region_size_z=16;in.start_z=8;
    in.target_x=&tx;in.target_y=&ty;in.target_z=&tz;in.target_count=1;in.pathfinding_malus=malus;in.pathfinding_malus_count=3;
    in.config.max_range=128;in.config.max_visited_nodes=8192;in.config.fudge=1.5F;in.max_up_step=1;in.max_fall_distance=3;
    int ac[24576],bc[24576];ent::PathfinderOutput ao{ac,8192},bo{bc,8192};ent::PathfinderScratch a,b;
    auto old=[&]{a.passable.assign((types.size()+63)/64,0);a.standing.assign((types.size()+63)/64,0);require(ent::find_path_into(in,ao,a),"path run");return double(ao.path_length);};
    auto candidate=[&]{require(ent::find_path_into(in,bo,b),"path run");return double(bo.path_length);};
    old();candidate();require(ao.path_length==bo.path_length&&std::equal(ac,ac+ao.path_length*3,bc),"path output");
    compare("path/search/reconstructed-old-clear",old,candidate);
}
void height_bench() {
    for(int bits : {1,2,3,4,5,6,7,8,9,16,32}) for(int depth : {0,15,31}) {
        const int epl=64/bits;std::vector<std::uint64_t> storage((4096+epl-1)/epl,0);const std::uint64_t mask[]={2};
        for(int z=0;z<16;++z)for(int x=0;x<16;++x) {
            const int y=15-depth;if(y<0)continue;
            int idx=(y*16+z)*16+x;storage[idx/epl]|=std::uint64_t{1}<<((idx%epl)*bits);
        }
        hm::SectionView section{storage.data(),storage.size(),bits,mask};std::int32_t a[256],b[256];
        auto old=[&]{auto count=heightmap_scheduling_reference::populate_scalar(&section,1,-64,1,-65,a);return double(count+a[0]);};
        auto candidate=[&]{auto count=hm::populate_scalar(&section,1,-64,1,-65,b);return double(count+b[0]);};
        old();candidate();require(std::equal(a,a+256,b),"height outputs");
        compare("height/scalar/bits="+std::to_string(bits)+"/depth="+std::to_string(depth),old,candidate);
        if(bits==4) compare("height/unchanged-4bit-dispatch-vs-new-scalar/depth="+std::to_string(depth),[&]{auto count=hm::populate(&section,1,-64,1,-65,a);return double(count+a[0]);},candidate);
    }
}
void los_bench() {
    for(int count : {1,4,16,256}) for(int length : {4,32,128}) for(int mode : {0,1,2}) {
        std::vector<double> fx(count,1.5),fy(count,1.5),fz(count,1.5),tx(count,1.5+length),ty(count,1.5),tz(count,1.5);
        std::vector<std::int8_t> mask(132*3*3,0);if(mode)mask[(1*3+1)*132+1+(mode==1?0:length/2)]=1;
        ent::LosInputs in{fx.data(),fy.data(),fz.data(),tx.data(),ty.data(),tz.data(),static_cast<std::size_t>(count),mask.data(),0,0,0,132,3,3};
        bool out[256];int steps=0,early=0;
        for(int i=0;i<count;++i){auto r=ent::check_line_of_sight(in,i);steps+=r.blocks_checked;early+=!r.has_line_of_sight&&r.blocks_checked<length+1;require(r.blocks_checked==(mode==0?length+1:mode==1?1:length/2+1),"LOS exact steps");}
        std::printf("LOS_SYNTH,count=%d,length=%d,mode=%d,mean_steps=%.3f,early_hit=%.3f\n",count,length,mode,double(steps)/count,double(early)/count);
        auto batch=[&]{ent::check_line_of_sight_batch(in,out);return out[0]?1.:0.;};
        compare("los/A-A/count="+std::to_string(count)+"/length="+std::to_string(length)+"/mode="+std::to_string(mode),batch,batch);
    }
}
int main(int argc,char**argv) {
    const std::string group=argc>1?argv[1]:"all";
    std::puts("kind,case,iterations,old_ns,new_ns,paired_speedup,p10,p90");
    if(group=="all"||group=="entity")entity_bench();
    if(group=="all"||group=="cache")cache_bench();
    if(group=="all"||group=="predicate")predicate_bench();
    if(group=="all"||group=="allowed")allowed_bench();
    if(group=="all"||group=="column")column_bench();
    if(group=="all"||group=="path")path_bench();
    if(group=="all"||group=="height")height_bench();
    if(group=="all"||group=="los")los_bench();
}
