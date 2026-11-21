// reminders-helper: read Apple Reminders lists with EventKit and
//   (a) write a reminders.json file for firetv-home (schema in README.md), and/or
//   (b) push open items into Home Assistant local todo entities (add-only).
//
// Everything comes from a JSON config file and/or flags; nothing is hardcoded.
// Home Assistant credentials come from the environment (HA_URL, HA_TOKEN) or the config file.
import EventKit
import Foundation

struct HAPush: Codable {
    var list: String      // Reminders list name
    var entity: String    // e.g. todo.shopping_list
}

struct HelperConfig: Codable {
    var lists: [String] = []        // Reminders lists to read; empty means all lists
    var output: String? = nil       // path of reminders.json to write
    var interval_seconds: Int = 0   // 0 = run once and exit
    var ha_url: String? = nil
    var ha_token: String? = nil
    var ha_push: [HAPush] = []
}

func fail(_ msg: String) -> Never {
    FileHandle.standardError.write(Data(("reminders-helper: " + msg + "\n").utf8))
    exit(1)
}

func parseArgs() -> HelperConfig {
    var cfg = HelperConfig()
    var args = Array(CommandLine.arguments.dropFirst())
    var configPath: String? = nil
    var flags: [(String, String)] = []
    while !args.isEmpty {
        let a = args.removeFirst()
        switch a {
        case "-h", "--help":
            print("""
            usage: reminders-helper [--config FILE] [--lists "A,B"] [--output FILE] [--interval SECONDS]
            Flags override the config file. HA_URL and HA_TOKEN in the environment override ha_url/ha_token.
            """)
            exit(0)
        case "--config", "--lists", "--output", "--interval":
            guard !args.isEmpty else { fail("missing value for \(a)") }
            let v = args.removeFirst()
            if a == "--config" { configPath = v } else { flags.append((a, v)) }
        default:
            fail("unknown argument \(a)")
        }
    }
    if let p = configPath {
        guard let d = FileManager.default.contents(atPath: p) else { fail("cannot read config \(p)") }
        do { cfg = try JSONDecoder().decode(HelperConfig.self, from: d) } catch { fail("bad config: \(error)") }
    }
    for (k, v) in flags {
        switch k {
        case "--lists": cfg.lists = v.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        case "--output": cfg.output = v
        case "--interval": cfg.interval_seconds = Int(v) ?? 0
        default: break
        }
    }
    let env = ProcessInfo.processInfo.environment
    if let u = env["HA_URL"], !u.isEmpty { cfg.ha_url = u }
    if let t = env["HA_TOKEN"], !t.isEmpty { cfg.ha_token = t }
    if cfg.output == nil && cfg.ha_push.isEmpty { fail("nothing to do: set an output path or ha_push entries") }
    return cfg
}

// MARK: EventKit

let store = EKEventStore()

func requestAccess() {
    let sem = DispatchSemaphore(value: 0)
    var granted = false
    if #available(macOS 14.0, *) {
        store.requestFullAccessToReminders { ok, _ in granted = ok; sem.signal() }
    } else {
        store.requestAccess(to: .reminder) { ok, _ in granted = ok; sem.signal() }
    }
    sem.wait()
    if !granted { fail("Reminders access denied. Allow it in System Settings > Privacy & Security > Reminders.") }
}

struct Item { let title: String; let due: String? }
struct ListOut { let name: String; var items: [Item] }

func dueString(_ c: DateComponents?) -> String? {
    guard let c = c, let date = Calendar.current.date(from: c) else { return nil }
    if c.hour == nil {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }
    let f = ISO8601DateFormatter()
    return f.string(from: date)
}

func readLists(_ names: [String]) -> [ListOut] {
    let all = store.calendars(for: .reminder)
    let chosen = names.isEmpty ? all : names.compactMap { n in all.first { $0.title == n } }
    var out: [ListOut] = []
    for cal in chosen {
        let sem = DispatchSemaphore(value: 0)
        var found: [EKReminder] = []
        let pred = store.predicateForIncompleteReminders(withDueDateStarting: nil, ending: nil, calendars: [cal])
        store.fetchReminders(matching: pred) { r in found = r ?? []; sem.signal() }
        sem.wait()
        let items = found
            .filter { !($0.title ?? "").isEmpty }
            .map { Item(title: $0.title ?? "", due: dueString($0.dueDateComponents)) }
        out.append(ListOut(name: cal.title, items: items))
    }
    for n in names where !all.contains(where: { $0.title == n }) {
        FileHandle.standardError.write(Data("reminders-helper: no Reminders list named \"\(n)\"\n".utf8))
    }
    return out
}

func writeJSON(_ lists: [ListOut], to path: String) {
    let obj: [String: Any] = [
        "updated": ISO8601DateFormatter().string(from: Date()),
        "lists": lists.map { l in
            ["name": l.name,
             "items": l.items.map { i in ["title": i.title, "due": i.due as Any? ?? NSNull()] }] as [String: Any]
        },
    ]
    do {
        let data = try JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted, .sortedKeys])
        // Write atomically so a reader never sees a half-written file.
        try data.write(to: URL(fileURLWithPath: path), options: .atomic)
    } catch { FileHandle.standardError.write(Data("reminders-helper: write failed: \(error)\n".utf8)) }
}

// MARK: Home Assistant (add-only sync)

func haCall(_ cfg: HelperConfig, _ path: String, _ body: [String: Any]) -> Data? {
    guard let base = cfg.ha_url?.trimmingCharacters(in: CharacterSet(charactersIn: "/")), !base.isEmpty,
          let token = cfg.ha_token, !token.isEmpty,
          let url = URL(string: base + path) else { return nil }
    var req = URLRequest(url: url, timeoutInterval: 15)
    req.httpMethod = "POST"
    req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
    req.httpBody = try? JSONSerialization.data(withJSONObject: body)
    let sem = DispatchSemaphore(value: 0)
    var result: Data? = nil
    URLSession.shared.dataTask(with: req) { d, resp, _ in
        if let h = resp as? HTTPURLResponse, (200..<300).contains(h.statusCode) { result = d ?? Data() }
        sem.signal()
    }.resume()
    sem.wait()
    return result
}

func pushToHA(_ cfg: HelperConfig, _ lists: [ListOut]) {
    for p in cfg.ha_push {
        guard let l = lists.first(where: { $0.name == p.list }) else { continue }
        let existingData = haCall(cfg, "/api/services/todo/get_items?return_response",
                                  ["entity_id": [p.entity], "status": "needs_action"])
        guard let d = existingData,
              let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let resp = j["service_response"] as? [String: Any],
              let ent = resp[p.entity] as? [String: Any],
              let arr = ent["items"] as? [[String: Any]] else {
            FileHandle.standardError.write(Data("reminders-helper: could not read \(p.entity) from Home Assistant\n".utf8))
            continue
        }
        let have = Set(arr.compactMap { $0["summary"] as? String })
        for item in l.items where !have.contains(item.title) {
            _ = haCall(cfg, "/api/services/todo/add_item", ["entity_id": p.entity, "item": item.title])
        }
    }
}

// MARK: main

let cfg = parseArgs()
requestAccess()
repeat {
    let lists = readLists(cfg.lists)
    if let out = cfg.output { writeJSON(lists, to: out) }
    if !cfg.ha_push.isEmpty { pushToHA(cfg, lists) }
    if cfg.interval_seconds > 0 { sleep(UInt32(cfg.interval_seconds)) }
} while cfg.interval_seconds > 0
