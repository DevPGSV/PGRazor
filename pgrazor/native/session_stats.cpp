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
#include <algorithm>
#include <atomic>
#include <array>
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

std::string nickname(void *pokemon) {
 try {
  Root name(field<void*>(pokemon,"nickname_","System.String"));
  if (!name) return "-";
  const int n=fn<int(*)(void*)>(exp("string_length"))(name.get());
  if (n<0 || n>32) return "-";
  const auto *chars=fn<const uint16_t*(*)(void*)>(boot.runtime.string_chars)(name.get());
  if (n && !chars) return "-";
  std::string hex;
  constexpr char digits[]="0123456789abcdef";
  for (int i=0;i<n;++i)
   for (int shift=12;shift>=0;shift-=4) hex+=digits[(chars[i]>>shift)&15];
  return hex;
 } catch (...) { return "-"; }
}

int display_enum(void *object,const char *name,const char *type_name) {
 try {
  void *member=fn<void*(*)(void*,const char*)>(boot.meta.class_get_field_from_name)(klass(object),name);
  require(member && !(fn<int(*)(void*)>(boot.meta.field_get_flags)(member)&0x10),name);
  void *type=fn<void*(*)(void*)>(boot.meta.field_get_type)(member);
  require(type && typename_of(type)==type_name,name);
  void *enum_class=fn<void*(*)(void*)>(boot.meta.class_from_type)(type);
  require(enum_class && fn<bool(*)(void*)>(boot.meta.class_is_enum)(enum_class),name);
  void *base=fn<void*(*)(void*)>(boot.meta.class_enum_basetype)(enum_class);
  require(base && typename_of(base)=="System.Int32",name);
  int32_t value=-1;
  fn<void(*)(void*,void*,void*)>(boot.runtime.field_get_value)(object,member,&value);
  return value>=0 ? value : -1;
 } catch (...) { return -1; }
}

std::string capture_display(void *pokemon) {
 constexpr const char *display_type="Holoholo.Rpc.PokemonDisplayProto";
 Root display(optional_field<void*>(pokemon,"pokemonDisplay_",display_type,nullptr));
 int shiny=-1, form=-1, costume=-1, gender=-1, alignment=-1, bread=-1;
 if (display && typename_of(fn<void*(*)(void*)>(boot.meta.class_get_type)(klass(display.get())))==display_type) {
  const auto value=optional_field<uint8_t>(display.get(),"Shiny","System.Boolean",255);
  if (value<=1) shiny=value;
  form=display_enum(display.get(),"Form","Holoholo.Rpc.PokemonDisplayProto.Types.Form");
  costume=display_enum(display.get(),"Costume","Holoholo.Rpc.PokemonDisplayProto.Types.Costume");
  gender=display_enum(display.get(),"Gender","Holoholo.Rpc.PokemonDisplayProto.Types.Gender");
  alignment=display_enum(display.get(),"Alignment","Holoholo.Rpc.PokemonDisplayProto.Types.Alignment");
  bread=display_enum(display.get(),"BreadModeEnum","Holoholo.Proto.Shared.Bread.BreadModeEnum.Types.Modifier");
 }
 return "|"+std::to_string(shiny)+"|"+std::to_string(form)+"|"+std::to_string(costume)
     +"|"+std::to_string(gender)+"|"+std::to_string(alignment)+"|"+std::to_string(bread);
}

std::string capture_pokemon(void *pokemon,uint64_t key) {
 require(key && field<uint64_t>(pokemon,"id_","System.UInt64")==key,"key mismatch");
 const bool egg=field<bool>(pokemon,"isEgg_","System.Boolean");
 auto number=[&](const char *name) { return optional_field<int32_t>(pokemon,name,"System.Int32",-1); };
 auto positive=[](int value) { return value>0 ? value : -1; };
 auto iv=[](int value) { return value>=0 && value<=15 ? value : -1; };
 auto flag=[&](const char *name) {
  const int value=optional_field<uint8_t>(pokemon,name,"System.Boolean",255);
  return value<=1 ? value : -1;
 };
 const float multiplier=optional_field<float>(pokemon,"cpMultiplier_","System.Single",-1);
 const int64_t creation=optional_field<int64_t>(pokemon,"creationTimeMs_","System.Int64",-1);
 const int values[]={positive(number("pokemonId_")),egg ? -1 : positive(number("cp_")),
  egg ? -1 : iv(number("individualAttack_")),egg ? -1 : iv(number("individualDefense_")),
  egg ? -1 : iv(number("individualStamina_")),flag("favorite_"),int(egg),
  egg ? -1 : number("stamina_"),egg ? -1 : positive(number("maxStamina_")),
  egg ? -1 : positive(number("move1_")),egg ? -1 : positive(number("move2_")),
  egg ? -1 : positive(number("move3_")),egg ? -1 : flag("isLucky_")};
 std::string row=std::to_string(key);
 for (int value:values) row+="|"+std::to_string(value);
 return row+"|"+std::to_string(!egg && std::isfinite(multiplier) && multiplier>0 ? multiplier : -1)
     +"|"+std::to_string(creation>0 ? creation : -1)+"|"+nickname(pokemon)+(egg ? "|-1|-1|-1|-1|-1|-1" : capture_display(pokemon));
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
 constexpr std::size_t max_samples=10;
 std::array<uint64_t,max_samples> seen{};
 Root node(head.get());
 std::string sample;
 const std::size_t count=std::min<std::size_t>(n,max_samples);
 for (std::size_t i=0;i<count;++i) {
  require(clock_ms()-started<20,"capture deadline");
  assert_type(node.get(),kNode);
  require(field<void*>(node.get(),"list",kList)==list.get(),"detached node");
  void *get=method(node.get(),"get_Value",0);
  require(!(fn<uint32_t(*)(void*,uint32_t*)>(boot.meta.method_get_flags)(get,nullptr)&0x10)
      && typename_of(fn<void*(*)(void*)>(boot.meta.method_get_return_type)(get))==kPair,"node getter signature");
  Root pair(invoke(get,node.get()));assert_type(pair.get(),kPair);
  const auto key=field<uint64_t>(pair.get(),"key","System.UInt64");
  for (std::size_t j=0;j<i;++j) require(seen[j]!=key,"duplicate key");
  seen[i]=key;
  Root pokemon(field<void*>(pair.get(),"value",kPokemon));assert_type(pokemon.get(),kPokemon);
  sample+=(i==0 ? "" : ";")+capture_pokemon(pokemon.get(),key);
  if (i+1<count) node=Root(field<void*>(node.get(),"next",kNode));
 }
 require(field<void*>(bag,"esej","Niantic.Holoholo.IPlayerService")==owner
      && field<void*>(bag,"esed",kMap)==map.get() && field<void*>(map.get(),"list",kList)==list.get()
      && field<void*>(list.get(),"head",kNode)==head.get()
      && field<int32_t>(list.get(),"version","System.Int32")==version
      && field<int32_t>(list.get(),"count","System.Int32")==n && clock_ms()-started<20,"bag changed");
 return std::to_string(n)+","+std::to_string(clock_ms()-started)+"|"+sample;
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
namespace {
constexpr int kExportRows = 2048, kExportSliceRows = 32;
constexpr uint64_t kSliceSoftMs = 4, kSliceHardMs = 12;
std::atomic<uint64_t> export_request{0}, export_lease{0}, export_deadline{0};
struct ExportCursor {
 uint64_t id=0, started=0;
 Root bag, player, map, list, head, next;
 std::u16string owner;
 int count=0, version=0, position=0;
};
ExportCursor cursor;
uint64_t finished_request=0;

bool emit_export(uint64_t request,uint64_t lease,const std::u16string &owner,
                 const std::string &data) {
 const auto *transport=static_cast<const PgrRuntimeTransportApi*>(pgrazor_runtime_transport_api(1));
 if(!transport)return false;
 std::string packet="export|"+std::to_string(request)+"|"+std::to_string(lease)+"|";
 constexpr char digits[]="0123456789abcdef";
 for(char16_t c:owner) for(int shift=12;shift>=0;shift-=4) packet+=digits[(c>>shift)&15];
 packet+="|"+data;
 const char event[]="research-pokemon-bag";
 const PgrRuntimeTransportString args[]={{event,sizeof(event)-1},{packet.data(),uint32_t(packet.size())}};
 return transport->emit(19,nullptr,0,args,2)==0;
}

void finish_export(uint64_t request) {
 finished_request=request;
 cursor=ExportCursor{};
}

void verify_export_source(void *bag,void *player) {
 require(cursor.bag.get()==bag && cursor.player.get()==player
      && field<void*>(bag,"esej","Niantic.Holoholo.IPlayerService")==player
      && field<void*>(bag,"esed",kMap)==cursor.map.get()
      && field<void*>(cursor.map.get(),"list",kList)==cursor.list.get()
      && field<void*>(cursor.list.get(),"head",kNode)==cursor.head.get()
      && field<int32_t>(cursor.list.get(),"version","System.Int32")==cursor.version
      && field<int32_t>(cursor.list.get(),"count","System.Int32")==cursor.count,"structure_changed");
}
}

void pgr_research_export_request(uint64_t request,uint64_t lease,uint64_t deadline) noexcept {
 export_request.store(0);
 export_lease.store(lease);
 export_deadline.store(deadline);
 export_request.store(request);
}

bool pgr_research_export_pending(uint64_t now,uint64_t lease) noexcept {
 const auto request=export_request.load();
 return request && request!=finished_request && lease && lease==export_lease.load()
     && now<export_deadline.load() && request==export_request.load();
}

void pgr_research_export_cleanup() noexcept {
 if (!cursor.id) return;
 const uint64_t now=clock_ms();
 if (cursor.id!=export_request.load()
     || !pgr_research_export_pending(now,pgr_research_progression_lease(now))) finish_export(cursor.id);
}

void pgr_research_export_slice(void *bag,void *player,const uint16_t *id,uint16_t length,
                               uint64_t lease,uint64_t started) noexcept {
 const auto request=export_request.load();
 if (!bag || !player || !id || !length || length>256
     || !pgr_research_export_pending(clock_ms(),lease) || request!=export_request.load()) return;
 try {
  std::unique_lock<std::mutex> lock(stats_mutex,std::try_to_lock);
  if(!lock)return;
  ensure_research_boot();
  const std::u16string owner(id,id+length);
  if (cursor.id!=request) {
   cursor=ExportCursor{};
   cursor.id=request; cursor.owner=owner; cursor.started=started;
   cursor.bag=Root(bag); cursor.player=Root(player);
   cursor.map=Root(field<void*>(bag,"esed",kMap));assert_type(cursor.map.get(),kMap);
   cursor.list=Root(field<void*>(cursor.map.get(),"list",kList));assert_type(cursor.list.get(),kList);
   cursor.count=field<int32_t>(cursor.list.get(),"count","System.Int32");
   require(cursor.count>0 && cursor.count<=kExportRows,"source_unavailable_or_over_limit");
   cursor.version=field<int32_t>(cursor.list.get(),"version","System.Int32");
   cursor.head=Root(field<void*>(cursor.list.get(),"head",kNode));assert_type(cursor.head.get(),kNode);
   cursor.next=Root(cursor.head.get());
  }
  require(cursor.owner==owner,"structure_changed");
  verify_export_source(bag,player);
  std::string rows;
  const int offset=cursor.position;
  while (cursor.position<cursor.count && cursor.position-offset<kExportSliceRows
         && rows.size()<10000 && clock_ms()-started<kSliceSoftMs) {
   void *node=cursor.next.get();
   assert_type(node,kNode);
   require(field<void*>(node,"list",kList)==cursor.list.get()
        && (cursor.position==0 || node!=cursor.head.get()),"structure_changed");
   void *get=method(node,"get_Value",0);
   require(!(fn<uint32_t(*)(void*,uint32_t*)>(boot.meta.method_get_flags)(get,nullptr)&0x10)
        && typename_of(fn<void*(*)(void*)>(boot.meta.method_get_return_type)(get))==kPair,"invalid_node");
   Root pair(invoke(get,node));assert_type(pair.get(),kPair);
   const auto key=field<uint64_t>(pair.get(),"key","System.UInt64");
   Root pokemon(field<void*>(pair.get(),"value",kPokemon));assert_type(pokemon.get(),kPokemon);
   if (!rows.empty()) rows+=';';
   rows+=capture_pokemon(pokemon.get(),key);
   cursor.next=Root(field<void*>(cursor.next.get(),"next",kNode));
   ++cursor.position;
  }
  verify_export_source(bag,player);
  const bool done=cursor.position==cursor.count;
  require(!done || cursor.next.get()==cursor.head.get(),"structure_changed");
  const uint64_t now=clock_ms(), slice=now-started;
  require(slice<kSliceHardMs,"slice_budget");
  if (!pgr_research_export_pending(now,lease) || request!=export_request.load()
       || lease!=pgr_research_progression_lease(now)) { finish_export(request); return; }
  if (rows.empty()) return;
  const bool emitted=emit_export(request,lease,owner,std::string(done?"done":"slice")+"|"
      +std::to_string(cursor.count)+"|"+std::to_string(offset)+"|"+std::to_string(cursor.started)+"|"
      +std::to_string(now)+"|"+std::to_string(slice)+"|"+rows);
  if (done || !emitted) finish_export(request);
 } catch (const Failure &failure) {
  try {
   if (request==export_request.load() && cursor.id==request) {
    const std::string reason=failure.where;
    emit_export(request,lease,cursor.owner,"failed|"+(reason=="structure_changed" || reason=="slice_budget"
      || reason=="source_unavailable_or_over_limit" ? reason : "invalid_source"));
   }
  } catch (...) {}
  finish_export(request);
 } catch (...) {
  finish_export(request);
 }
}

#endif
