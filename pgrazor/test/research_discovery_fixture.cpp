#include "../native/research-discovery/discovery.cpp"

void pgr_collection_request(bool, uint64_t, uint64_t, uint64_t) noexcept {}

#include <cassert>
#include <cstdlib>
#include <cstring>

namespace {
struct Type { const char *ns; const char *name; const char *full; };
struct Field { const char *name; const char *type; };
struct Method { const char *name; const char *result; const char *argument; };
Type bag{"Niantic.Holoholo", "SyntheticInventoryBag", "Niantic.Holoholo.SyntheticInventoryBag"};
Type entry{"Holoholo.Rpc", "SyntheticJournalEntry", "Holoholo.Rpc.SyntheticJournalEntry"};
Type denied{"Niantic.Holoholo", "SyntheticAuthSession", "Niantic.Holoholo.SyntheticAuthSession"};
Type elsewhere{"Unrelated.Library", "SyntheticInventory", "Unrelated.Library.SyntheticInventory"};
Type itemBag{"Niantic.Holoholo.Internal", "ItemBagImpl", "Niantic.Holoholo.Internal.ItemBagImpl"};
Type itemBagState{"", "hwl", "Niantic.Holoholo.Internal.ItemBagImpl.hwl"};
Type *classes[]{&bag, &entry, &denied, &elsewhere, &itemBag};
const char *imageName = "holo-fixture.dll";
Field fields[]{{"OwnedCount", "System.Int32"}, {"AuthToken", "System.String"}};
Method method{"GetJournalEntry", "Holoholo.Rpc.SyntheticJournalEntry", "System.Int64"};
void *images[]{&classes};
bool ready = true;
int attachments = 0;

void *domain() { return &images; }
void *assemblies(void *, size_t *count) { *count = 1; return images; }
void *image(void *assembly) { return assembly; }
void *exact(void *, const char *ns, const char *name) {
    for (auto *type : classes)
        if (!std::strcmp(ns, type->ns) && !std::strcmp(name, type->name)) return type;
    return nullptr;
}
void *nestedType(void *owner, void **state) {
    if (owner != &itemBag || *state) return nullptr;
    return *state = &itemBagState;
}
void *classType(void *klass) { return const_cast<char *>(static_cast<Type *>(klass)->full); }
char *typeNameFixture(const void *type) { return ::strdup(static_cast<const char *>(type)); }
void *iterMethods(void *, void **state) {
    if (*state) return nullptr;
    return *state = &method;
}
const char *methodName(void *value) { return static_cast<Method *>(value)->name; }
const void *methodReturn(void *value) { return static_cast<Method *>(value)->result; }
const void *methodArgument(void *value, uint32_t) { return static_cast<Method *>(value)->argument; }
uint32_t oneArgument(void *) { return 1; }
const void *fieldType(void *value) { return static_cast<Field *>(value)->type; }
bool notEnum(void *) { return false; }
void *threadCurrent() { return nullptr; }
void *attachFixture(void *owner) { ++attachments; return owner; }
void detachFixture(void *) { --attachments; }

int snapshot(PgrBootBinding *boot) {
    if (!ready) return -1;
    Dl_info info{};
    boot->handle = dlopen(nullptr, RTLD_NOW);
    assert(dladdr(reinterpret_cast<void *>(&snapshot), &info));
    boot->module_base = info.dli_fbase;
    boot->domain = domain();
#define BIND(section, field, value) boot->section.field = reinterpret_cast<void *>(&value)
    BIND(meta, domain_get, domain);
    BIND(meta, domain_get_assemblies, assemblies);
    BIND(meta, assembly_get_image, image);
    BIND(meta, class_from_name, exact);
    BIND(meta, class_get_nested_types, nestedType);
    BIND(meta, class_get_type, classType);
    BIND(meta, type_get_name, typeNameFixture);
    BIND(meta, mem_free, std::free);
    BIND(meta, class_is_enum, notEnum);
    BIND(meta, class_get_methods, iterMethods);
    BIND(meta, method_get_name, methodName);
    BIND(meta, method_get_return_type, methodReturn);
    BIND(meta, method_get_param_count, oneArgument);
    BIND(meta, method_get_param, methodArgument);
    BIND(meta, field_get_type, fieldType);
    BIND(runtime, thread_current, threadCurrent);
    BIND(runtime, thread_attach, attachFixture);
    BIND(runtime, thread_detach, detachFixture);
#undef BIND
    return 0;
}
}

extern "C" {
const char *il2cpp_image_get_name(void *) { return imageName; }
const char *il2cpp_class_get_name(void *klass) { return static_cast<Type *>(klass)->name; }
const char *il2cpp_class_get_namespace(void *klass) { return static_cast<Type *>(klass)->ns; }
size_t il2cpp_image_get_class_count(const void *) { return sizeof(classes) / sizeof(classes[0]); }
void *il2cpp_image_get_class(const void *, size_t index) { return classes[index]; }
void *il2cpp_class_get_fields(void *, void **state) {
    if (*state == &fields[1]) return nullptr;
    return *state = *state ? &fields[1] : &fields[0];
}
const char *il2cpp_field_get_name(void *field) { return static_cast<Field *>(field)->name; }
const void *pgrazor_runtime_bootstrap_api(uint32_t) {
    static const PgrBootApi fixture{1, sizeof(PgrBootApi), nullptr, snapshot};
    return &fixture;
}
}

int main() {
    assert(pgr_research_progression_lease(1) == 0);
    setProgressionLease(nullptr, nullptr, 9, 100);
    assert(pgr_research_progression_lease(99) == 9);
    assert(pgr_research_progression_lease(100) == 0);
    setProgressionLease(nullptr, nullptr, 0, 0);
    assert(pgr_research_progression_lease(1) == 0);
    assert(reviewedBreadType("Holoholo.Proto.Shared.Bread", "BreadMoveSlotProto"));
    assert(reviewedBreadType("Holoholo.Proto.Shared.Bread", "BreadModeEnum.Types.Modifier"));
    assert(!reviewedBreadType("Holoholo.Proto.Shared.Bread", "Other"));
    assert(!allowedNamespace("Holoholo.Proto.Shared.Bread"));
    auto support = observe("support", "", "", "", "", "", 0, 10);
    assert(support.find("\"search\":true") != std::string::npos);
    assert(support.find("\"properties\":false") != std::string::npos);
    auto page = observe("search", "holo-fixture.dll", "", "synthetic", "", "", 0, 1);
    assert(page.find("SyntheticInventoryBag") != std::string::npos && page.find("\"next_offset\":1") != std::string::npos);
    auto next = observe("search", "holo-fixture.dll", "", "synthetic", "", "", 1, 10);
    assert(next.find("scan_progress") != std::string::npos);
    assert(next.find("SyntheticJournalEntry") != std::string::npos);
    assert(next.find("AuthSession") == std::string::npos && next.find("Unrelated") == std::string::npos);
    auto methods = observe("type", "", bag.ns, bag.name, "methods", "journal", 0, 10);
    assert(methods.find("System.Int64") != std::string::npos && methods.find("GetJournalEntry") != std::string::npos);
    auto typed = observe("type", "", bag.ns, bag.name, "fields", "", 0, 10);
    assert(typed.find("System.Int32") != std::string::npos && typed.find("AuthToken") == std::string::npos);
    assert(observe("type", "", bag.ns, "Missing", "methods", "", 0, 10).find("type_not_found") != std::string::npos);
    constexpr const char *nestedName = "ItemBagImpl.hwl";
    assert(observe("type", "", itemBag.ns, nestedName, "fields", "", 0, 10)
        .find("type_not_found") != std::string::npos);
    imageName = "holo-game-player-model.dll";
    auto nested = observe("type", imageName, itemBag.ns, nestedName, "fields", "", 0, 10);
    assert(nested.find("OwnedCount") != std::string::npos && nested.find("AuthToken") == std::string::npos);
    assert(nested.find(itemBagState.full) != std::string::npos);
    assert(observe("type", imageName, itemBag.ns, "ItemBagImpl.Other", "fields", "", 0, 10)
        .find("type_not_found") != std::string::npos);
    assert(attachments == 0);
    ready = false;
    assert(observe("support", "", "", "", "", "", 0, 10).find("runtime_not_ready") != std::string::npos);
}
