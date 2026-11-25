#include <jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <chrono>
#include <cctype>
#include <string>
#include <string_view>

#include "runtime_bootstrap.h"

namespace {
using Clock = std::chrono::steady_clock;
constexpr size_t kBudget = 7000;
constexpr int kImageStride = 100000;
constexpr char kUnavailable[] = "{\"availability\":\"unavailable\",\"reason\":\"runtime_not_ready\",\"provenance\":\"il2cpp_runtime\"}";

template<class F> F fn(void *address) { return reinterpret_cast<F>(address); }

template<class F> F optional(const PgrBootBinding &boot, const char *name) {
    void *address = dlsym(boot.handle, name);
    Dl_info info{};
    return address && dladdr(address, &info) && info.dli_fbase == boot.module_base ? fn<F>(address) : nullptr;
}

using Ptr = void *(*)(void *);
using Label = const char *(*)(void *);
using Iterate = void *(*)(void *, void **);

std::string label(const char *value) {
    if (!value) return {};
    std::string text;
    for (size_t i = 0; i < 240 && value[i]; ++i) {
        unsigned char byte = value[i];
        if (byte < 32 || byte > 126) return {};
        text += char(byte);
    }
    return text.size() == 240 ? std::string{} : text;
}

std::string lower(std::string text) {
    for (char &c : text) c = char(std::tolower(static_cast<unsigned char>(c)));
    return text;
}

bool redacted(const std::string &name) {
    const auto key = lower(name);
    for (auto word : {"auth", "credential", "token", "password", "secret", "security", "crypt",
                      "integrity", "antiabuse", "attestation", "login", "session", "identity",
                      "account", "email", "deviceid", "playerid", "userid", "accesskey"}) {
        if (key.find(word) != std::string::npos) return true;
    }
    return false;
}

bool allowedNamespace(const std::string &ns) {
    return (ns == "Holoholo.Rpc" || ns.rfind("Holoholo.Rpc.", 0) == 0 ||
            ns == "Niantic.Holoholo" || ns.rfind("Niantic.Holoholo.", 0) == 0) && !redacted(ns);
}

std::string quoted(const std::string &value) {
    if (value.empty() || redacted(value)) return "null";
    std::string out = "\"";
    for (char c : value) {
        if (c == '"' || c == '\\') out += '\\';
        out += c;
    }
    return out + '"';
}

std::string typeName(const PgrBootBinding &boot, const void *type) {
    if (!type) return {};
    auto *name = fn<char *(*)(const void *)>(boot.meta.type_get_name)(type);
    std::string copy = label(name);
    if (name) fn<void (*)(void *)>(boot.meta.mem_free)(name);
    return copy;
}

struct Attachment {
    const PgrBootBinding &boot;
    void *attached = nullptr;
    bool ready = false;
    Attachment(const PgrBootBinding &binding, void *domain) : boot(binding) {
        ready = fn<void *(*)()>(boot.runtime.thread_current)() != nullptr;
        if (!ready) {
            attached = fn<Ptr>(boot.runtime.thread_attach)(domain);
            ready = attached != nullptr;
        }
    }
    ~Attachment() { if (attached) fn<void (*)(void *)>(boot.runtime.thread_detach)(attached); }
};

struct Exports {
    Label imageName, className, classNamespace, fieldName, propertyName;
    size_t (*classCount)(const void *);
    void *(*imageClass)(const void *, size_t);
    Iterate fields, properties, interfaces;
    Ptr propertyGet, propertySet, parent;

    explicit Exports(const PgrBootBinding &boot)
      : imageName(optional<Label>(boot, "il2cpp_image_get_name")),
        className(optional<Label>(boot, "il2cpp_class_get_name")),
        classNamespace(optional<Label>(boot, "il2cpp_class_get_namespace")),
        fieldName(optional<Label>(boot, "il2cpp_field_get_name")),
        propertyName(optional<Label>(boot, "il2cpp_property_get_name")),
        classCount(optional<decltype(classCount)>(boot, "il2cpp_image_get_class_count")),
        imageClass(optional<decltype(imageClass)>(boot, "il2cpp_image_get_class")),
        fields(optional<Iterate>(boot, "il2cpp_class_get_fields")),
        properties(optional<Iterate>(boot, "il2cpp_class_get_properties")),
        interfaces(optional<Iterate>(boot, "il2cpp_class_get_interfaces")),
        propertyGet(optional<Ptr>(boot, "il2cpp_property_get_get_method")),
        propertySet(optional<Ptr>(boot, "il2cpp_property_get_set_method")),
        parent(optional<Ptr>(boot, "il2cpp_class_get_parent")) {}
};

bool allowedAssembly(const std::string &image) {
    return image.rfind("holo-", 0) == 0 && !redacted(image);
}

std::string flag(bool available) { return available ? "true" : "false"; }

struct Page {
    std::string items;
    int size = 0;
    bool add(const std::string &value, int limit) {
        if (size >= limit || items.size() + value.size() + 1 > kBudget) return false;
        if (size++) items += ',';
        items += value;
        return true;
    }
    std::string json(int next, const std::string &extra = {}) const {
        return "{\"availability\":\"observed\",\"provenance\":\"il2cpp_runtime\",\"next_offset\":"
            + (next < 0 ? "null" : std::to_string(next)) + ",\"items\":[" + items + "]" + extra + "}";
    }
};

bool matches(const std::string &value, const std::string &q) {
    return q.empty() || lower(value).find(lower(q)) != std::string::npos;
}

std::string clsRow(const PgrBootBinding &boot, void *klass, const std::string &assembly,
                   const std::string &ns, const std::string &name) {
    return "{\"assembly\":" + quoted(assembly) + ",\"namespace\":" + quoted(ns) + ",\"name\":" + quoted(name)
        + ",\"type\":" + quoted(typeName(boot, fn<Ptr>(boot.meta.class_get_type)(klass)))
        + ",\"enum\":" + flag(fn<bool (*)(void *)>(boot.meta.class_is_enum)(klass)) + "}";
}

std::string missing(const char *reason) {
    return std::string("{\"availability\":\"unavailable\",\"provenance\":\"il2cpp_runtime\",\"reason\":\"")
        + reason + "\"}";
}

std::string members(const PgrBootBinding &boot, const Exports &x, void *klass,
                    const std::string &assembly, const std::string &ns, const std::string &name,
                    const std::string &kind, const std::string &q, int offset, int limit,
                    Clock::time_point deadline) {
    const auto methods = fn<Iterate>(boot.meta.class_get_methods);
    Iterate iterate = kind == "fields" ? x.fields : kind == "properties" ? x.properties
        : kind == "interfaces" ? x.interfaces : methods;
    Label memberName = kind == "fields" ? x.fieldName : kind == "properties" ? x.propertyName
        : kind == "interfaces" ? nullptr : fn<Label>(boot.meta.method_get_name);
    if (!iterate || (kind != "interfaces" && !memberName)) return missing("member_export_unavailable");

    auto resultType = fn<const void *(*)(void *)>(boot.meta.method_get_return_type);
    auto fieldsType = fn<const void *(*)(void *)>(boot.meta.field_get_type);
    auto param = fn<const void *(*)(void *, uint32_t)>(boot.meta.method_get_param);
    auto count = fn<uint32_t (*)(void *)>(boot.meta.method_get_param_count);
    const bool isEnum = fn<bool (*)(void *)>(boot.meta.class_is_enum)(klass);
    const auto baseType = isEnum ? fn<Ptr>(boot.meta.class_enum_basetype)(klass) : nullptr;
    const auto parent = x.parent ? x.parent(klass) : nullptr;
    auto classType = fn<Ptr>(boot.meta.class_get_type);
    Page page;
    void *state = nullptr;
    int index = 0;
    int next = -1;
    for (; index < 1024; index++) {
        if (Clock::now() >= deadline) return missing("observation_deadline");
        void *member = iterate(klass, &state);
        if (!member) break;
        if (index < offset) continue;
        const std::string item = memberName ? label(memberName(member)) : typeName(boot, classType(member));
        if (item.empty() || redacted(item) || !matches(item, q)) continue;
        const void *type = kind == "fields" ? fieldsType(member) : nullptr;
        if (kind == "properties" && x.propertyGet) {
            void *getter = x.propertyGet(member);
            if (getter) type = resultType(getter);
            else if (x.propertySet) {
                void *setter = x.propertySet(member);
                if (setter && count(setter)) type = param(setter, count(setter) - 1);
            }
        }
        std::string row = "{\"name\":" + quoted(item);
        if (kind == "methods") {
            row += ",\"returns\":" + quoted(typeName(boot, resultType(member))) + ",\"parameters\":[";
            const uint32_t total = count(member);
            for (uint32_t i = 0; i < std::min(total, 16u); i++) {
                if (i) row += ',';
                row += quoted(typeName(boot, param(member, i)));
            }
            row += "],\"parameters_truncated\":" + flag(total > 16);
        } else if (kind != "interfaces") {
            row += ",\"type\":" + quoted(typeName(boot, type));
            if (kind == "fields" && isEnum) {
                row += ",\"literal\":" + flag((fn<uint32_t (*)(void *)>(boot.meta.field_get_flags)(member) & 0x40) != 0);
            }
        }
        if (!page.add(row + "}", limit)) { next = index; break; }
    }
    return page.json(next, ",\"declaration\":" + clsRow(boot, klass, assembly, ns, name)
        + ",\"parent\":" + quoted(parent ? typeName(boot, classType(parent)) : std::string{})
        + ",\"enum_underlying_type\":" + quoted(typeName(boot, baseType))
        + ",\"scan_limit_reached\":" + flag(index == 1024));
}

std::string observe(const std::string &op, const std::string &assembly, const std::string &ns,
                    const std::string &name, const std::string &kind, const std::string &q,
                    int offset, int limit) {
    const auto deadline = Clock::now() + std::chrono::milliseconds(350);
    const auto *api = static_cast<const PgrBootApi *>(pgrazor_runtime_bootstrap_api(1));
    PgrBootBinding boot{};
    boot.version = 1;
    boot.size = sizeof(boot);
    if (!api || api->snapshot(&boot)) return kUnavailable;
    void *domain = fn<void *(*)()>(boot.meta.domain_get)();
    if (!domain || domain != boot.domain) return kUnavailable;
    Attachment attachment(boot, domain);
    if (!attachment.ready) return kUnavailable;
    Exports x(boot);
    if (op == "support") {
        return "{\"availability\":\"observed\",\"provenance\":\"il2cpp_runtime\",\"assemblies\":" + flag(x.imageName != nullptr)
            + ",\"search\":" + flag(x.imageName && x.classCount && x.imageClass && x.className && x.classNamespace)
            + ",\"exact\":true,\"methods\":" + flag(boot.meta.class_get_methods != nullptr)
            + ",\"fields\":" + flag(x.fields && x.fieldName)
            + ",\"properties\":" + flag(x.properties && x.propertyName)
            + ",\"interfaces\":" + flag(x.interfaces != nullptr) + ",\"inheritance\":" + flag(x.parent != nullptr) + "}";
    }
    size_t total = 0;
    auto list = static_cast<void **>(fn<void *(*)(void *, size_t *)>(boot.meta.domain_get_assemblies)(domain, &total));
    if (!list || !total || total > PGR_MD_ASM_MAX) return missing("assembly_limit_or_unavailable");
    if (op == "assemblies" && !x.imageName) return missing("assembly_export_unavailable");
    if (op == "search" && !(x.imageName && x.classCount && x.imageClass && x.className && x.classNamespace))
        return missing("type_enumeration_exports_unavailable");
    if (op == "type" && !allowedNamespace(ns)) return missing("namespace_outside_scope");
    if ((op == "search" || op == "type") && !assembly.empty() && !x.imageName)
        return missing("assembly_filter_unavailable");
    Page page;
    size_t start = op == "search" ? size_t(offset / kImageStride) : op == "assemblies" ? size_t(offset) : 0;
    size_t scanned = 0;
    void *found = nullptr;
    std::string foundAssembly;
    for (size_t i = start; i < total; i++) {
        if (Clock::now() >= deadline) return missing("observation_deadline");
        void *image = fn<Ptr>(boot.meta.assembly_get_image)(list[i]);
        if (!image) continue;
        auto imageName = x.imageName ? label(x.imageName(image)) : std::string{};
        if ((x.imageName && !allowedAssembly(imageName)) || (!assembly.empty() && imageName != assembly)) continue;
        if (op == "assemblies") {
            if (!page.add(quoted(imageName), limit)) return page.json(int(i));
        } else if (op == "type") {
            void *candidate = fn<void *(*)(void *, const char *, const char *)>(boot.meta.class_from_name)(
                image, ns.c_str(), name.c_str());
            if (candidate) {
                if (found && candidate != found) return missing("ambiguous_type");
                found = candidate;
                foundAssembly = imageName;
            }
        } else {
            const auto count = x.classCount(image);
            if (count > kImageStride) return missing("image_limit");
            for (size_t j = i == start ? size_t(offset % kImageStride) : 0; j < count; j++) {
                const int cursor = int(i * kImageStride + j);
                if (scanned++ >= 4096 || Clock::now() >= deadline) return page.json(cursor);
                void *klass = x.imageClass(image, j);
                if (!klass) continue;
                auto currentNs = label(x.classNamespace(klass));
                auto currentName = label(x.className(klass));
                if (!allowedNamespace(currentNs) || currentName.empty() || redacted(currentName)
                        || (!ns.empty() && currentNs != ns) || !matches(currentName, name)) continue;
                if (!page.add(clsRow(boot, klass, imageName, currentNs, currentName), limit)) return page.json(cursor);
            }
        }
    }
    if (op == "type") return found ? members(boot, x, found, foundAssembly, ns, name, kind, q, offset, limit, deadline)
        : missing("type_not_found");
    return page.json(-1);
}

std::string input(JNIEnv *env, jstring value) {
    if (!value || env->GetStringLength(value) > 96) return {};
    const char *text = env->GetStringUTFChars(value, nullptr);
    if (!text) return {};
    auto result = label(text);
    env->ReleaseStringUTFChars(value, text);
    return result;
}

jstring JNICALL query(JNIEnv *env, jclass, jstring operation, jstring assembly, jstring ns,
                     jstring name, jstring kind, jstring filter, jint offset, jint limit) {
    const auto op = input(env, operation);
    const auto member = input(env, kind);
    const auto type = input(env, name);
    if (offset < 0 || offset > 51200000 || limit < 1 || limit > 20 || redacted(type)
            || (op != "support" && op != "assemblies" && op != "search" && op != "type")
            || (op == "type" && member != "fields" && member != "properties"
                && member != "methods" && member != "interfaces")) return env->NewStringUTF(missing("invalid_request").c_str());
    auto result = observe(op, input(env, assembly), input(env, ns), type, member, input(env, filter), offset, limit);
    return env->NewStringUTF(result.c_str());
}
}

int pgrazor_register_research_discovery(JNIEnv *env) {
    const auto owner = env->FindClass("dev/pgrazor/ResearchDiscoveryNative");
    if (!owner) return -1;
    const JNINativeMethod method = {const_cast<char *>("query"),
        const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)Ljava/lang/String;"),
        reinterpret_cast<void *>(&query)};
    const int result = env->RegisterNatives(owner, &method, 1);
    env->DeleteLocalRef(owner);
    return result;
}
