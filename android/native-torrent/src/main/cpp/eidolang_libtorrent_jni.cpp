#include <jni.h>
#include <libtorrent/session.hpp>
#include <libtorrent/session_params.hpp>
#include <libtorrent/settings_pack.hpp>
#include <libtorrent/load_torrent.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/bencode.hpp>
#include <libtorrent/bdecode.hpp>
#include <libtorrent/entry.hpp>
#include <libtorrent/kademlia/item.hpp>
#include <memory>
#include <string>
#include <vector>
#include <array>
#include <chrono>

namespace lt=libtorrent;
struct Engine { std::unique_ptr<lt::session> ses; std::string state_path; };
static Engine* e(jlong h){return reinterpret_cast<Engine*>(h);} 
static std::string js(JNIEnv* env,jstring s){ const char* p=env->GetStringUTFChars(s,nullptr); std::string r(p); env->ReleaseStringUTFChars(s,p); return r; }

extern "C" JNIEXPORT jlong JNICALL Java_org_eidolang_native_torrent_LibtorrentNative_createSession(JNIEnv* env,jobject,jstring state,jstring,jint port){
    lt::settings_pack sp; sp.set_bool(lt::settings_pack::enable_dht,true); sp.set_bool(lt::settings_pack::enable_lsd,true); sp.set_bool(lt::settings_pack::enable_upnp,true); sp.set_bool(lt::settings_pack::enable_natpmp,true); sp.set_int(lt::settings_pack::connections_limit,80); sp.set_str(lt::settings_pack::listen_interfaces,"0.0.0.0:"+std::to_string(port)+",[::]:"+std::to_string(port));
    auto x=new Engine; x->state_path=js(env,state); x->ses=std::make_unique<lt::session>(lt::session_params(sp)); return reinterpret_cast<jlong>(x);
}
extern "C" JNIEXPORT void JNICALL Java_org_eidolang_native_torrent_LibtorrentNative_destroySession(JNIEnv*,jobject,jlong h){ delete e(h); }
extern "C" JNIEXPORT jlong JNICALL Java_org_eidolang_native_torrent_LibtorrentNative_addTorrentFile(JNIEnv* env,jobject,jlong h,jstring torrent,jstring save,jboolean seed){
    auto atp=lt::load_torrent_file(js(env,torrent)); atp.save_path=js(env,save); if(seed) atp.flags|=lt::torrent_flags::seed_mode; e(h)->ses->async_add_torrent(std::move(atp)); return 1;
}
extern "C" JNIEXPORT jlong JNICALL Java_org_eidolang_native_torrent_LibtorrentNative_addMagnet(JNIEnv* env,jobject,jlong h,jstring magnet,jstring save){ auto atp=lt::parse_magnet_uri(js(env,magnet)); atp.save_path=js(env,save); e(h)->ses->async_add_torrent(std::move(atp)); return 1; }
extern "C" JNIEXPORT jstring JNICALL Java_org_eidolang_native_torrent_LibtorrentNative_pollEvent(JNIEnv* env,jobject,jlong h,jint timeout){ auto a=e(h)->ses->wait_for_alert(std::chrono::milliseconds(timeout)); if(!a) return nullptr; std::vector<lt::alert*> alerts; e(h)->ses->pop_alerts(&alerts); if(alerts.empty()) return nullptr; return env->NewStringUTF(alerts.front()->message().c_str()); }
extern "C" JNIEXPORT void JNICALL Java_org_eidolang_native_torrent_LibtorrentNative_dhtGetMutable(JNIEnv* env,jobject,jlong h,jbyteArray key,jbyteArray salt){ if(env->GetArrayLength(key)!=32) return; std::array<char,32> k{}; env->GetByteArrayRegion(key,0,32,reinterpret_cast<jbyte*>(k.data())); std::string s(env->GetArrayLength(salt),'\0'); env->GetByteArrayRegion(salt,0,s.size(),reinterpret_cast<jbyte*>(s.data())); e(h)->ses->dht_get_item(k,s); }
// Pre-signed BEP44 PUT requires mapping the supplied k/salt/seq/sig/v fields into dht_put_item callback.
// This adapter is intentionally fail-closed until the bdecode->entry conversion is completed and Android-NDK tested.
extern "C" JNIEXPORT void JNICALL Java_org_eidolang_native_torrent_LibtorrentNative_dhtPutPreSigned(JNIEnv* env,jobject,jlong,jbyteArray){ jclass ex=env->FindClass("java/lang/UnsupportedOperationException"); env->ThrowNew(ex,"R17 native BEP44 PUT awaits NDK conformance; Kotlin/reference semantics are implemented"); }
extern "C" JNIEXPORT void JNICALL Java_org_eidolang_native_torrent_LibtorrentNative_saveSessionState(JNIEnv*,jobject,jlong){ /* wired after Android NDK admission */ }
