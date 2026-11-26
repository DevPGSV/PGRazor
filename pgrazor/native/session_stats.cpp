#include "session_stats.h"
#include "accounts.h"
#include "shared_metadata.h"
#include "runtime_bootstrap.h"
#include "runtime_transport.h"
#ifdef PGRAZOR_RESEARCH_DISCOVERY
#include "research-discovery/progression.h"
#endif
#include <dlfcn.h>
#include <time.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <map>
#include <cmath>
#include <mutex>
#include <string>

// Session stats: the app keeps a rolling history of the player's lifetime counters to show what changed
// in the last hour and the last 24 hours. This reads them from the game's own PlayerService, the same
// numbers the profile page shows, so every catch and spin counts whoever made it. Nothing is sent to the
// game's servers.
namespace {
using namespace pgr_shared_meta;
struct Failure {const char *where;};
void require(bool ok,const char *where){if(!ok)throw Failure{where};}
template<class F>F fn(void *p){require(p,"missing runtime export");return reinterpret_cast<F>(p);}
PgrBootBinding boot{};const PgrSharedMetadataOutput *md=nullptr;
uint64_t owner=0,next_sample=0,services_at=0,sent_at=0;bool busy=false,ready=false;
std::string sent;
std::mutex stats_mutex;
// A reading every 10 s is two managed calls and a few field reads. The service lookup, which searches the
// scene, runs once a minute, and an unchanged reading is sent once a minute.
constexpr uint64_t kSampleMs=10000,kServicesMs=60000,kResendMs=60000,kRetryMs=15000;
uint64_t clock_ms(){timespec t{};clock_gettime(CLOCK_BOOTTIME,&t);return uint64_t(t.tv_sec)*1000+t.tv_nsec/1000000;}
void *exp(const char *name){std::string n="il2cpp_";n+=name;void *p=dlsym(boot.handle,n.c_str());require(p,name);return p;}
struct Root {
 uintptr_t h=0;
 Root()=default;explicit Root(void *v){if(v){h=fn<uintptr_t(*)(void*,int)>(boot.runtime.gchandle_new)(v,0);require(h,"GC root");}}
 Root(const Root&)=delete;Root& operator=(const Root&)=delete;
 Root(Root &&r) noexcept:h(r.h){r.h=0;}
 Root& operator=(Root &&r) noexcept {if(this!=&r){reset();h=r.h;r.h=0;}return *this;}
 ~Root(){reset();}
 void reset(){if(h){fn<void(*)(uintptr_t)>(boot.runtime.gchandle_free)(h);h=0;}}
 void *get()const{return h?fn<void*(*)(uintptr_t)>(boot.runtime.gchandle_get_target)(h):nullptr;}
 explicit operator bool()const{return get()!=nullptr;}
};
void *klass(void *o){require(o,"null object");return fn<void*(*)(void*)>(boot.runtime.object_get_class)(o);}
std::string class_name(void *o){return fn<const char*(*)(void*)>(exp("class_get_name"))(klass(o));}
void *cls(const char *ns,const char *name){
 static std::map<std::string,void*> cache;std::string key=std::string(ns)+"."+name;auto it=cache.find(key);if(it!=cache.end())return it->second;
 const auto *api=static_cast<const PgrMetaApi*>(pgrazor_runtime_metadata_api(1));require(api,"metadata API");
 PgrMetaClassQuery q{};q.version=1;q.size=sizeof(q);q.name_space=ns;q.name=name;q.canonical_match=PGR_MD_MATCH_EXACT;
 PgrMetaClassResult out{};require(api->resolve_class(&q,&out)==0,name);return cache[key]=const_cast<void*>(out.klass);
}
std::string typename_of(void *type){char *p=fn<char*(*)(void*)>(boot.meta.type_get_name)(type);require(p,"type name");std::string s=p;fn<void(*)(void*)>(boot.meta.mem_free)(p);return s;}
template<class T>T field(void *o,const char *name,const char *type){
 void *f=fn<void*(*)(void*,const char*)>(boot.meta.class_get_field_from_name)(klass(o),name);require(f,name);
 require(!(fn<int(*)(void*)>(boot.meta.field_get_flags)(f)&0x10),name);
 require(typename_of(fn<void*(*)(void*)>(boot.meta.field_get_type)(f))==type,name);
 T v{};fn<void(*)(void*,void*,void*)>(boot.runtime.field_get_value)(o,f,&v);return v;
}
void *method(void *o,const char *name,int count){void *m=fn<void*(*)(void*,const char*,int)>(boot.meta.class_get_method_from_name)(klass(o),name,count);require(m,name);require(fn<unsigned(*)(void*)>(boot.meta.method_get_param_count)(m)==unsigned(count),name);return m;}
void *invoke(void *m,void *o,void **args=nullptr){void *ex=nullptr;void *v=fn<void*(*)(void*,void*,void**,void**)>(boot.runtime.runtime_invoke)(m,o,args,&ex);require(!ex,"managed call failed");return v;}
void *call(void *o,const char *name,int n=0,void **args=nullptr){return invoke(method(o,name,n),o,args);}
void *h(unsigned id){return const_cast<void*>(md->opt_handles[id]);}
Root type_object(void *k){return Root(fn<void*(*)(void*)>(exp("type_get_object"))(fn<void*(*)(void*)>(boot.meta.class_get_type)(k)));}
Root resolve(const char *ns,const char *name){
 Root type=type_object(h(H_IL_MAP));void *args[]={type.get()};Root map(invoke(h(H_IL_FIND_M),nullptr,args));if(!map)return {};
 Root container(invoke(h(H_IL_CONTAINER_M),map.get()));if(!container)return {};
 Root target=type_object(cls(ns,name));void *resolve_args[]={target.get()};return Root(invoke(h(H_IL_RESOLVE_M),container.get(),resolve_args));
}
void event(const std::string &json){
 auto tr=static_cast<const PgrRuntimeTransportApi*>(pgrazor_runtime_transport_api(1));if(!tr)return;
 PgrRuntimeTransportString strings[]={{"session-stats",13},{json.c_str(),uint32_t(json.size())}};tr->emit(19,nullptr,0,strings,2);
}
struct Stats { long long xp; int caught, seen, stops, hatched; float km; };
Stats read_stats(void *stats) {
 return {field<int64_t>(stats,"Experience","System.Int64"),
         field<int32_t>(stats,"NumPokemonCaptured","System.Int32"),
         field<int32_t>(stats,"NumPokemonEncountered","System.Int32"),
         field<int32_t>(stats,"PokeStopVisits","System.Int32"),
         field<int32_t>(stats,"NumEggsHatched","System.Int32"),
         field<float>(stats,"KmWalked","System.Single")};
}
Root player,iap;  // cached game services
void step(){
 if(!ready){auto api=static_cast<const PgrBootApi*>(pgrazor_runtime_bootstrap_api(1));boot.version=1;boot.size=sizeof(boot);require(md&&api&&api->snapshot(&boot)==0,"Runtime unavailable");ready=true;}
 const uint64_t now=clock_ms();if(now<next_sample)return;next_sample=now+kRetryMs;
 // Looked up again each minute: the game rebuilds its services after an account switch.
 if(!player||now-services_at>=kServicesMs){
  player=resolve("Niantic.Holoholo","IPlayerService");services_at=now;
  try{iap=resolve("Niantic.Holoholo.Storefront","IIapService");}catch(const Failure&){iap.reset();}
 }
 if(!player)return;
 Root stats(call(player.get(),"get_PlayerStats"));if(!stats)return;  // not loaded yet after sign-in
 require(class_name(stats.get())=="PlayerStatsProto","Player stats identity");
 const auto values=read_stats(stats.get());
 const auto xp=values.xp;
 const auto caught=values.caught, seen=values.seen, stops=values.stops, hatched=values.hatched;
 const auto km=values.km;
 if(xp<=0)return;  // an empty proto before the player's data arrives
 // The stardust balance the game's own screens show; -1 when it cannot be read.
 long long dust=-1;
 try{
  if(iap){void *box=call(iap.get(),"get_Stardusts");
   if(box&&class_name(box)=="Int32"){
    void *p=fn<void*(*)(void*)>(boot.runtime.object_unbox)(box);int v=-1;
    if(p)std::memcpy(&v,p,sizeof(v));
    if(v>=0)dust=v;}}}
 catch(const Failure&){}
 char json[256];
 std::snprintf(json,sizeof(json),"{\"xp\":%lld,\"caught\":%d,\"seen\":%d,\"stops\":%d,\"km\":%.3f,\"hatched\":%d,\"dust\":%lld}",xp,caught,seen,stops,double(km),hatched,dust);
 if(sent!=json||now-sent_at>=kResendMs){event(json);sent=json;sent_at=now;}
 next_sample=now+kSampleMs;
}
}  // namespace

void pgr_session_stats_tick(uint64_t tid,const PgrSharedMetadataOutput *metadata){
 std::unique_lock<std::mutex> guard_lock(stats_mutex,std::try_to_lock);
 if(!guard_lock)return;
 if(busy||!tid||tid!=uint64_t(gettid())||(owner&&owner!=tid)||!metadata)return;
 struct Guard{Guard(){busy=true;}~Guard(){busy=false;}}guard;
 owner=tid;md=metadata;
 try{step();}
 catch(const Failure &f){next_sample=clock_ms()+kServicesMs;player.reset();iap.reset();event(std::string("{\"error\":\"")+f.where+"\"}");}
 catch(...){next_sample=clock_ms()+kServicesMs;player.reset();iap.reset();}
}

bool pgr_local_api_trainer_requested() noexcept {
 const auto *transport=static_cast<const PgrRuntimeTransportApi*>(pgrazor_runtime_transport_api(1));
 int demand=0;
 return transport&&!transport->get_bool(41,&demand)&&demand;
}

void pgr_local_api_trainer(void *current, const uint16_t *id, uint16_t id_len,
                           const uint16_t *name, uint16_t name_len, uint64_t observed_ms) noexcept {
 try {
  const auto *transport=static_cast<const PgrRuntimeTransportApi*>(pgrazor_runtime_transport_api(1));
  int demand=0;
  if(!transport||transport->get_bool(41,&demand)||!demand||!current||!id_len||!name_len
      ||id_len>256||name_len>128||!observed_ms)return;
  // The account owner passes its still-rooted identity source. No cached PlayerService or IAP
  // service enters this read path. Existing SessionStats history runs independently.
  std::unique_lock<std::mutex> guard_lock(stats_mutex,std::try_to_lock);
  if(!guard_lock)return;
  char counters[256]{};
#ifdef PGRAZOR_RESEARCH_DISCOVERY
  const uint64_t research = pgr_research_progression_lease(clock_ms());
  char progression[128]{};
#endif
  try {
   if(!ready){
    auto api=static_cast<const PgrBootApi*>(pgrazor_runtime_bootstrap_api(1));
    boot.version=1;boot.size=sizeof(boot);
    require(api&&api->snapshot(&boot)==0,"Runtime unavailable");ready=true;
   }
   Root stats(call(current,"get_PlayerStats"));
   if(stats&&class_name(stats.get())=="PlayerStatsProto"){
    const auto v=read_stats(stats.get());
#ifdef PGRAZOR_RESEARCH_DISCOVERY
    if(research && v.xp>0) {
     try {
      const auto level=field<int32_t>(stats.get(),"Level","System.Int32");
      const auto previous=field<int64_t>(stats.get(),"PrevLevelExp","System.Int64");
      const auto next=field<int64_t>(stats.get(),"NextLevelExp","System.Int64");
      if(level>0) std::snprintf(progression,sizeof(progression),"|%llu|%d|%lld|%lld|%lld",
          static_cast<unsigned long long>(research),level,v.xp,
          static_cast<long long>(previous>=0 && previous<=v.xp ? previous : -1),
          static_cast<long long>(next>0 && next>previous ? next : -1));
     } catch (...) { /* The trainer snapshot remains independent. */ }
    }
#endif
    if(v.xp>0&&v.caught>=0&&v.seen>=0&&v.stops>=0&&v.hatched>=0&&std::isfinite(v.km)&&v.km>=0){
     // Integer millikilometers avoid locale-dependent decimal formatting.
     if(v.km<1e9f)std::snprintf(counters,sizeof(counters),"|%lld|%d|%d|%d|%lld|%d",
         v.xp,v.caught,v.seen,v.stops,std::llround(double(v.km)*1000),v.hatched);
    }
   }
  } catch (...) { /* The authenticated name remains observable. */ }
  if(transport->get_bool(41,&demand)||!demand)return;
  const auto *accounts=pgrazor_accounts_api(1);
  pgrazor_account_snapshot_v1_t identity{};
  if(!accounts)return;
  accounts->api_sample(&identity);
  if(!identity.has_identity||identity.logging_out||identity.queue_overflow_latched
      ||identity.last_identity_ms!=observed_ms||identity.id_len!=id_len
      ||std::memcmp(identity.id,id,id_len*sizeof(uint16_t)))return;
  const auto append_hex=[](std::string &out,const uint16_t *data,uint16_t size){
   const char digits[]="0123456789abcdef";
   for(uint16_t i=0;i<size;i++)for(int shift=12;shift>=0;shift-=4)out.push_back(digits[(data[i]>>shift)&15]);
  };
  std::string packet=std::to_string(observed_ms)+"|";
  append_hex(packet,id,id_len);packet.push_back('|');append_hex(packet,name,name_len);packet+=counters;
  const char kind[]="local-api-trainer";
  PgrRuntimeTransportString args[]={{kind,sizeof(kind)-1},{packet.data(),uint32_t(packet.size())}};
  transport->emit(19,nullptr,0,args,2);
#ifdef PGRAZOR_RESEARCH_DISCOVERY
  if(progression[0] && research == pgr_research_progression_lease(clock_ms())) {
   std::string payload=std::to_string(observed_ms)+"|";
   append_hex(payload,id,id_len);payload+=progression;
   const char kind[]="research-progression";
   PgrRuntimeTransportString research_args[]={{kind,sizeof(kind)-1},{payload.data(),uint32_t(payload.size())}};
   transport->emit(19,nullptr,0,research_args,2);
  }
#endif
 } catch (...) {}
}

#ifdef PGRAZOR_RESEARCH_DISCOVERY
namespace {
constexpr const char *kPair = "System.Collections.Generic.KeyValuePair<System.UInt64,Holoholo.Rpc.PokemonProto>";
constexpr const char *kList = "System.Collections.Generic.LinkedList<System.Collections.Generic.KeyValuePair<System.UInt64,Holoholo.Rpc.PokemonProto>>";
constexpr const char *kNode = "System.Collections.Generic.LinkedListNode<System.Collections.Generic.KeyValuePair<System.UInt64,Holoholo.Rpc.PokemonProto>>";
constexpr const char *kMap = "Niantic.Holoholo.Collections.LinkedHashMap<System.UInt64,Holoholo.Rpc.PokemonProto>";
constexpr const char *kPokemon = "Holoholo.Rpc.PokemonProto";

void ensure_research_boot() {
 if (ready) return;
 const auto *api = static_cast<const PgrBootApi*>(pgrazor_runtime_bootstrap_api(1));
 boot.version=1;boot.size=sizeof(boot);
 require(api && api->snapshot(&boot)==0,"runtime unavailable");
 ready=true;
}

void assert_type(void *object,const char *expected) {
 auto getter=fn<void*(*)(void*)>(boot.meta.class_get_type);
 require(typename_of(getter(klass(object)))==expected,"class mismatch");
}

template<class T>T optional_field(void *o, const char *name, const char *type, T unknown) {
 try { return field<T>(o,name,type); } catch (...) { return unknown; }
}

std::string capture_bag(void *bag,void *owner,uint64_t started) {
 require(clock_ms()-started<20,"capture deadline");
 require(field<void*>(bag,"esej","Niantic.Holoholo.IPlayerService")==owner,"bag owner mismatch");
 Root map(field<void*>(bag,"esed",kMap));assert_type(map.get(),kMap);
 Root list(field<void*>(map.get(),"list",kList));assert_type(list.get(),kList);
 const int n=field<int32_t>(list.get(),"count","System.Int32");
 const int version=field<int32_t>(list.get(),"version","System.Int32");
 // An empty backing list can mean initialization is incomplete.
 require(n>0 && n<=30000,"bag not ready");
 Root head(field<void*>(list.get(),"head",kNode));assert_type(head.get(),kNode);
 require(field<void*>(head.get(),"list",kList)==list.get(),"detached head");
 void *get=method(head.get(),"get_Value",0);
 require(!(fn<uint32_t(*)(void*,uint32_t*)>(boot.meta.method_get_flags)(get,nullptr)&0x10)
     && typename_of(fn<void*(*)(void*)>(boot.meta.method_get_return_type)(get))==kPair,"node getter signature");
 Root pair(invoke(get,head.get()));assert_type(pair.get(),kPair);
 const auto key=field<uint64_t>(pair.get(),"key","System.UInt64");
 Root pokemon(field<void*>(pair.get(),"value",kPokemon));assert_type(pokemon.get(),kPokemon);
 require(key && field<uint64_t>(pokemon.get(),"id_","System.UInt64")==key,"key mismatch");
 const int egg=field<bool>(pokemon.get(),"isEgg_","System.Boolean");
 const int species=optional_field<int32_t>(pokemon.get(),"pokemonId_","System.Int32",-1);
 const int cp=egg ? -1 : optional_field<int32_t>(pokemon.get(),"cp_","System.Int32",-1);
 const int attack=egg ? -1 : optional_field<int32_t>(pokemon.get(),"individualAttack_","System.Int32",-1);
 const int defense=egg ? -1 : optional_field<int32_t>(pokemon.get(),"individualDefense_","System.Int32",-1);
 const int stamina=egg ? -1 : optional_field<int32_t>(pokemon.get(),"individualStamina_","System.Int32",-1);
 const int favorite=optional_field<uint8_t>(pokemon.get(),"favorite_","System.Boolean",255);
 require(field<void*>(bag,"esej","Niantic.Holoholo.IPlayerService")==owner
      && field<void*>(bag,"esed",kMap)==map.get() && field<void*>(map.get(),"list",kList)==list.get()
      && field<void*>(list.get(),"head",kNode)==head.get()
      && field<int32_t>(list.get(),"version","System.Int32")==version
      && field<int32_t>(list.get(),"count","System.Int32")==n && clock_ms()-started<20,"bag changed");
 auto iv=[](int value) { return value>=0 && value<=15 ? value : -1; };
 return std::to_string(n)+"|"+std::to_string(key)+"|"+std::to_string(species>0?species:-1)+"|"+
     std::to_string(cp>0?cp:-1)+"|"+std::to_string(iv(attack))+"|"+std::to_string(iv(defense))+"|"+
     std::to_string(iv(stamina))+"|"+std::to_string(favorite<=1?favorite:-1)+"|"+std::to_string(egg);
}
}

bool pgr_research_bag_classes(void **contract,void **implementation) noexcept {
 try {
  std::unique_lock<std::mutex> lock(stats_mutex,std::try_to_lock);
  if(!lock) return false;
  ensure_research_boot();
  *contract=cls("Niantic.Holoholo","IPokemonBag");
  *implementation=cls("Niantic.Holoholo.Internal","PokemonBagImpl");
  return *contract && *implementation;
 } catch (...) { return false; }
}

void pgr_research_bag_observed(void *bag,void *player,const uint16_t *id,uint16_t length,
                              uint64_t observed,uint64_t lease,uint64_t started) noexcept {
 try {
  if (!bag || !player || !id || !length || length>256 || lease!=pgr_research_progression_lease(clock_ms())) return;
  std::unique_lock<std::mutex> lock(stats_mutex,std::try_to_lock);
  if(!lock) return;
  ensure_research_boot();
  Root owner(player), storage(bag);
  std::string sample=capture_bag(storage.get(),owner.get(),started);
  if (lease!=pgr_research_progression_lease(clock_ms())) return;
  const auto *accounts=pgrazor_accounts_api(1);
  const auto *transport=static_cast<const PgrRuntimeTransportApi*>(pgrazor_runtime_transport_api(1));
  if(!accounts||!transport)return;
  pgrazor_account_snapshot_v1_t identity{};
  accounts->api_sample(&identity);
  if (!identity.has_identity || identity.logging_out || identity.queue_overflow_latched
       || identity.last_identity_ms!=observed || identity.id_len!=length
       || std::memcmp(identity.id,id,length*sizeof(uint16_t))) return;
  std::string packet=std::to_string(observed)+"|";
  const char digits[]="0123456789abcdef";
  for(uint16_t i=0;i<length;++i) for(int shift=12;shift>=0;shift-=4) packet+=digits[(id[i]>>shift)&15];
  packet+="|"+std::to_string(lease)+"|"+sample;
  const char event[]="research-pokemon-bag";
  PgrRuntimeTransportString args[]={{event,sizeof(event)-1},{packet.data(),uint32_t(packet.size())}};
  transport->emit(19,nullptr,0,args,2);
 } catch (...) {}
}
#endif
