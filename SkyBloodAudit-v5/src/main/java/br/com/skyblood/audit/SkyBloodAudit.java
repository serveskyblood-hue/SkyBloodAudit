package br.com.skyblood.audit;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public final class SkyBloodAudit extends JavaPlugin implements Listener {
 private IntegrationManager integrations;
 private MacroGuard macroGuard;
 record Prov(long at,String why){} record DMsg(String title,String body,int color,boolean critical){}
 private Economy eco; private Connection db;
 private volatile long lastEveryone=0L;
 private final Map<UUID,Double> balances=new HashMap<>();
 private final Map<UUID,Map<Material,Integer>> inv=new HashMap<>();
 private final Map<UUID,Prov> prov=new HashMap<>();
 private final Map<String,Set<UUID>> fingerprintOwners=new HashMap<>();
 private final Queue<DMsg> dq=new ConcurrentLinkedQueue<>();
 private final HttpClient http=HttpClient.newHttpClient();
 private BukkitTask et,dt,wt; private final DateTimeFormatter tf=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

 public void onEnable(){saveDefaultConfig();setupEco();setupDb();macroGuard=new MacroGuard(this);getServer().getPluginManager().registerEvents(this,this); integrations=new IntegrationManager(this); integrations.load();for(Player p:Bukkit.getOnlinePlayers())init(p);tasks();record("SYSTEM",null,"START","v5.0 iniciado",null,null,null,false);}
 public void onDisable(){for(BukkitTask t:new BukkitTask[]{et,dt,wt})if(t!=null)t.cancel();try{if(db!=null)db.close();}catch(Exception ignored){}}
 private void setupEco(){RegisteredServiceProvider<Economy> r=getServer().getServicesManager().getRegistration(Economy.class);eco=r==null?null:r.getProvider();}
 private void setupDb(){if(!getConfig().getBoolean("database.enabled",true))return;try{Files.createDirectories(getDataFolder().toPath());String f=getConfig().getString("database.file","audit.db");db=DriverManager.getConnection("jdbc:sqlite:"+getDataFolder().toPath().resolve(f));try(Statement s=db.createStatement()){s.executeUpdate("PRAGMA journal_mode=WAL");s.executeUpdate("CREATE TABLE IF NOT EXISTS audit_events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT NOT NULL, type TEXT NOT NULL, player_uuid TEXT, player_name TEXT, action TEXT, detail TEXT, world TEXT, x INTEGER, y INTEGER, z INTEGER, critical INTEGER NOT NULL DEFAULT 0)");s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_player ON audit_events(player_name,ts)");s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_type ON audit_events(type,ts)");s.executeUpdate("CREATE TABLE IF NOT EXISTS item_fingerprints(id INTEGER PRIMARY KEY AUTOINCREMENT, ts TEXT, fingerprint TEXT, player_uuid TEXT, player_name TEXT, material TEXT, amount INTEGER, detail TEXT)");s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_fp ON item_fingerprints(fingerprint,ts)");}}catch(Exception e){getLogger().severe("SQLite: "+e.getMessage());db=null;}}
 private void tasks(){long ei=Math.max(1,getConfig().getLong("economy.check-interval-ticks",20));et=Bukkit.getScheduler().runTaskTimer(this,this::scanEco,20,ei);long di=Math.max(1,getConfig().getLong("antidupe.scan-interval-ticks",10));dt=Bukkit.getScheduler().runTaskTimer(this,this::scanInv,20,di);wt=Bukkit.getScheduler().runTaskTimerAsynchronously(this,()->{for(int i=0;i<10;i++){DMsg m=dq.poll();if(m==null)break;webhook(m);}},20,20);}
 private void init(Player p){inv.put(p.getUniqueId(),counts(p));if(eco!=null)try{balances.put(p.getUniqueId(),eco.getBalance(p));}catch(Throwable ignored){}}
 private boolean staff(Player p){String q=getConfig().getString("staff.permission","skybloodaudit.staff");if(p.hasPermission(q))return true;for(String x:getConfig().getStringList("staff.fallback-permissions"))if(p.hasPermission(x))return true;return false;}
 private int col(Player p){return getConfig().getInt(staff(p)?"discord.staff-color":"discord.player-color");}

 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e){Player p=e.getPlayer();String raw=e.getMessage().replaceFirst("^/","");String f=first(raw);if(getConfig().getStringList("commands.provenance-prefixes").stream().anyMatch(x->x.equalsIgnoreCase(f))) source(p,"comando /"+f);
        for(String plugin : getConfig().getConfigurationSection("integrations").getKeys(false)){
            if(!getConfig().getBoolean("integrations."+plugin+".enabled",true)) continue;
            for(String hint:getConfig().getStringList("integrations."+plugin+".command-hints"))
                if(hint.equalsIgnoreCase(f)) source(p,"origem correlacionada: "+plugin+" via /"+f);
        }boolean crit=critical(raw);String title=staff(p)?"🔴 COMANDO DE STAFF":"🔵 COMANDO DE JOGADOR";if(isPermissionChange(raw)){crit=true;title="🛡️ ALTERAÇÃO CRÍTICA DE PERMISSÕES";}emit("COMMAND",p,title,"/"+sanitize(raw),crit,isPermissionChange(raw)?getConfig().getInt("discord.permission-color"):col(p));if(f.equals("give"))markTarget(raw,"/give por "+p.getName());}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void console(org.bukkit.event.server.ServerCommandEvent e){String r=e.getCommand();boolean pc=isPermissionChange(r);emitRaw("COMMAND",null,"CONSOLE",pc?"🛡️ ALTERAÇÃO DE PERMISSÕES":"⚫ COMANDO DO CONSOLE","/"+sanitize(r),pc||critical(r),pc?getConfig().getInt("discord.permission-color"):getConfig().getInt("discord.staff-color"));if(first(r).equals("give"))markTarget(r,"/give do console");}
 private boolean isPermissionChange(String r){String s=r.toLowerCase(Locale.ROOT);return (first(r).equals("lp")||first(r).equals("luckperms"))&&(s.contains(" parent ")||s.contains(" permission ")||s.contains(" group ")||s.contains(" set ")||s.contains(" unset "));}
 private void markTarget(String r,String why){String[] a=r.split("\\s+");if(a.length>1){Player t=Bukkit.getPlayerExact(a[1]);if(t!=null)source(t,why);}}

 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p && e.getView().getTopInventory().getType()!=InventoryType.CRAFTING){
        String title=e.getView().getTitle(); String reason="GUI/container: "+title;
        if(getConfig().getConfigurationSection("integrations")!=null)
          for(String plugin:getConfig().getConfigurationSection("integrations").getKeys(false))
            for(String hint:getConfig().getStringList("integrations."+plugin+".gui-hints"))
              if(title.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT))) reason="origem correlacionada: "+plugin+" GUI "+title;
        source(p,reason);
        macroGuard.pulse(p,"CLICK");
    }}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p)source(p,"pickup");}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void craft(CraftItemEvent e){if(e.getWhoClicked() instanceof Player p)source(p,"craft");}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void attack(EntityDamageByEntityEvent e){if(e.getDamager() instanceof Player p)macroGuard.pulse(p,"ATTACK");}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void interact(PlayerInteractEvent e){Action a=e.getAction();if(a==Action.RIGHT_CLICK_BLOCK||a==Action.RIGHT_CLICK_AIR)macroGuard.pulse(e.getPlayer(),"INTERACT");}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void breakBlock(BlockBreakEvent e){macroGuard.pulse(e.getPlayer(),"BREAK");}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void creative(InventoryCreativeEvent e){if(!(e.getWhoClicked() instanceof Player p))return;source(p,"creative");ItemStack i=e.getCursor();if(i!=null&&!i.getType().isAir())emit("CREATIVE",p,"🚨 ITEM VIA CREATIVE",i.getType()+" x"+i.getAmount(),true,getConfig().getInt("discord.staff-color"));}
 @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void gm(PlayerGameModeChangeEvent e){emit("GAMEMODE",e.getPlayer(),"🎮 GAMEMODE",e.getPlayer().getGameMode()+" -> "+e.getNewGameMode(),e.getNewGameMode()==GameMode.CREATIVE,col(e.getPlayer()));}
 @EventHandler public void join(PlayerJoinEvent e){init(e.getPlayer());emit("JOIN",e.getPlayer(),"ENTRADA","online",false,col(e.getPlayer()));}
 @EventHandler public void quit(PlayerQuitEvent e){Player p=e.getPlayer();emit("QUIT",p,"SAÍDA","offline",false,col(p));balances.remove(p.getUniqueId());inv.remove(p.getUniqueId());prov.remove(p.getUniqueId());macroGuard.forget(p);}

 private void scanEco(){if(eco==null||!getConfig().getBoolean("economy.enabled",true))return;double min=getConfig().getDouble("economy.minimum-change",.01),big=getConfig().getDouble("economy.critical-change",1e6);for(Player p:Bukkit.getOnlinePlayers()){double n;try{n=eco.getBalance(p);}catch(Throwable t){continue;}Double b=balances.put(p.getUniqueId(),n);if(b==null)continue;double d=n-b;if(Math.abs(d)<min)continue;source(p,"economia "+(d>=0?"+":"")+money(d));emit("ECONOMY",p,"💰 ALTERAÇÃO DE SALDO","Antes "+money(b)+" | Depois "+money(n)+" | Variação "+(d>=0?"+":"")+money(d)+" | Provider "+eco.getName(),Math.abs(d)>=big,getConfig().getInt("discord.economy-color"));}}
 private void scanInv(){if(!getConfig().getBoolean("antidupe.enabled",true))return;int normal=getConfig().getInt("antidupe.alert-minimum-gain",16),highMin=getConfig().getInt("antidupe.high-value-minimum-gain",2);Set<String> high=new HashSet<>();for(String x:getConfig().getStringList("antidupe.high-value-materials"))high.add(x.toUpperCase(Locale.ROOT));fingerprintOwners.clear();for(Player p:Bukkit.getOnlinePlayers()){Map<Material,Integer> n=counts(p),o=inv.put(p.getUniqueId(),n);if(o!=null)for(var x:n.entrySet()){int gain=x.getValue()-o.getOrDefault(x.getKey(),0);if(gain<=0)continue;int th=high.contains(x.getKey().name())?highMin:normal;if(gain<th)continue;Prov pr=recent(p);if(pr==null)emit("ANTIDUPE",p,"🚨 POSSÍVEL DUPE","+"+gain+" "+x.getKey()+" sem origem conhecida nos últimos "+getConfig().getLong("antidupe.grace-seconds",5)+"s. Nenhuma punição automática.",true,getConfig().getInt("discord.antidupe-color"));else record("ANTIDUPE_LEGIT",p,"GAIN","+"+gain+" "+x.getKey()+" | "+pr.why(),null,null,null,false);}scanFingerprints(p);}}
 private void scanFingerprints(Player p){if(!getConfig().getBoolean("fingerprints.enabled",true))return;for(ItemStack it:p.getInventory().getContents()){if(it==null||it.getType().isAir()||!custom(it))continue;String fp=fingerprint(it);Set<UUID> owners=fingerprintOwners.computeIfAbsent(fp,k->new HashSet<>());owners.add(p.getUniqueId());saveFp(p,it,fp);if(owners.size()>1&&getConfig().getBoolean("fingerprints.alert-identical-custom-stacks",true))emit("FINGERPRINT",p,"🚨 FINGERPRINT CUSTOM REPETIDO","Fingerprint "+fp.substring(0,12)+"… visto em mais de um jogador. Isto é evidência para revisão, não ban automático.",true,getConfig().getInt("discord.antidupe-color"));}}
 private boolean custom(ItemStack i){if(!i.hasItemMeta())return false;ItemMeta m=i.getItemMeta();return m.hasDisplayName()||m.hasLore()||m.hasCustomModelData()||!m.getPersistentDataContainer().isEmpty();}
 private String fingerprint(ItemStack i){try{MessageDigest md=MessageDigest.getInstance("SHA-256");String s=i.getType()+"|"+i.getItemMeta().toString();byte[] b=md.digest(s.getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(b);}catch(Exception e){return Integer.toHexString(i.hashCode());}}
 private void saveFp(Player p,ItemStack i,String fp){if(db==null)return;try(PreparedStatement q=db.prepareStatement("INSERT INTO item_fingerprints(ts,fingerprint,player_uuid,player_name,material,amount,detail) VALUES(?,?,?,?,?,?,?)")){q.setString(1,Instant.now().toString());q.setString(2,fp);q.setString(3,p.getUniqueId().toString());q.setString(4,p.getName());q.setString(5,i.getType().name());q.setInt(6,i.getAmount());q.setString(7,"inventory scan");q.executeUpdate();}catch(Exception ignored){}}
 private Map<Material,Integer> counts(Player p){Map<Material,Integer> m=new EnumMap<>(Material.class);for(ItemStack i:p.getInventory().getContents())if(i!=null&&!i.getType().isAir())m.merge(i.getType(),i.getAmount(),Integer::sum);return m;}
 private void source(Player p,String why){prov.put(p.getUniqueId(),new Prov(System.currentTimeMillis(),why));}
 private Prov recent(Player p){Prov x=prov.get(p.getUniqueId());return x!=null&&System.currentTimeMillis()-x.at()<=getConfig().getLong("antidupe.grace-seconds",5)*1000?x:null;}

 private void emit(String type,Player p,String title,String detail,boolean critical,int color){String body=id(p)+"\n"+detail+"\n"+loc(p);emitRaw(type,p,title,title,body,critical,color);}
 private void emitRaw(String type,Player p,String action,String title,String body,boolean critical,int color){record(type,p,action,body,null,null,null,critical);if(getConfig().getBoolean("discord.enabled",true))dq.offer(new DMsg(title,body,color,critical));}
 private void record(String type,Player p,String action,String detail,String world,Integer x,Integer y,boolean critical){Location l=p==null?null:p.getLocation();String w=world!=null?world:(l==null?null:l.getWorld().getName());Integer xx=x!=null?x:(l==null?null:l.getBlockX()), yy=y!=null?y:(l==null?null:l.getBlockY()), zz=l==null?null:l.getBlockZ();String line="["+LocalDateTime.now().format(tf)+"] ["+type+"] "+(p==null?"":p.getName()+" | ")+detail;getLogger().info(line);if(getConfig().getBoolean("local-log.enabled",true))try{Path d=getDataFolder().toPath().resolve("logs");Files.createDirectories(d);Files.writeString(d.resolve(LocalDate.now()+".log"),line+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}if(db!=null)try(PreparedStatement q=db.prepareStatement("INSERT INTO audit_events(ts,type,player_uuid,player_name,action,detail,world,x,y,z,critical) VALUES(?,?,?,?,?,?,?,?,?,?,?)")){q.setString(1,Instant.now().toString());q.setString(2,type);q.setString(3,p==null?null:p.getUniqueId().toString());q.setString(4,p==null?null:p.getName());q.setString(5,action);q.setString(6,detail);q.setString(7,w);if(xx==null)q.setNull(8,Types.INTEGER);else q.setInt(8,xx);if(yy==null)q.setNull(9,Types.INTEGER);else q.setInt(9,yy);if(zz==null)q.setNull(10,Types.INTEGER);else q.setInt(10,zz);q.setInt(11,critical?1:0);q.executeUpdate();}catch(Exception e){getLogger().warning("DB write: "+e.getMessage());}}
 private String sanitize(String r){String f=first(r);for(String s:getConfig().getStringList("commands.sensitive"))if(f.equalsIgnoreCase(s))return f+" [ARGUMENTOS OCULTADOS]";return r;}
 private boolean critical(String r){String f=first(r);return getConfig().getStringList("commands.critical").stream().anyMatch(x->x.equalsIgnoreCase(f));}
 private String first(String r){return r.trim().split("\\s+")[0].toLowerCase(Locale.ROOT).replaceFirst("^/","");}
 private String id(Player p){return "Jogador: "+p.getName()+" | UUID: "+p.getUniqueId()+" | Classe: "+(staff(p)?"STAFF":"JOGADOR");}
 private String loc(Player p){Location l=p.getLocation();return "Mundo "+l.getWorld().getName()+" | X "+l.getBlockX()+" Y "+l.getBlockY()+" Z "+l.getBlockZ();}
 private String money(double d){return String.format(Locale.US,"$%,.2f",d);}
 private void webhook(DMsg m){String u=getConfig().getString("discord.webhook-url","");if(u==null||u.isBlank()||u.contains("COLE_SEU_WEBHOOK"))return;
        String content="",allowed="";
        if(m.critical()&&getConfig().getBoolean("discord.mention-everyone-on-critical",true)){
            long cooldown=Math.max(0,getConfig().getLong("discord.everyone-cooldown-seconds",60))*1000L;
            long now=System.currentTimeMillis();
            if(now-lastEveryone>=cooldown){lastEveryone=now;content="\"content\":\""+js("@everyone ")+"\",";allowed="\"allowed_mentions\":{\"parse\":[\"everyone\"]},";}
        }
        String json="{"+content+allowed+"\"username\":\""+js(getConfig().getString("discord.username","SkyBlood Audit"))+"\",\"embeds\":[{\"title\":\""+js(m.title())+"\",\"description\":\""+js(m.body())+"\",\"color\":"+m.color()+",\"footer\":{\"text\":\"SkyBloodAudit v5\"}}]}";try{HttpRequest q=HttpRequest.newBuilder(URI.create(u)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();http.send(q,HttpResponse.BodyHandlers.discarding());}catch(Exception e){getLogger().warning("Webhook: "+e.getMessage());}}
 private String js(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","").replace("\n","\\n");}
 public void integrationSource(Player p,String plugin,String detail){
   source(p,"CONFIRMADO "+plugin+": "+detail);
   record("INTEGRATION",p,plugin,detail,null,null,null,false);
 }
 public void integrationAlert(Player p,String plugin,String detail,boolean critical){
   source(p,"CONFIRMADO "+plugin+": "+detail);
   emit("INTEGRATION",p,"✅ ORIGEM CONFIRMADA • "+plugin,detail,critical,critical?getConfig().getInt("discord.antidupe-color"):getConfig().getInt("discord.economy-color"));
 }
 public void integrationSystem(String plugin,String detail,boolean critical){
   emitRaw("INTEGRATION",null,plugin,(critical?"⚠️ ":"✅ ")+plugin,detail,critical,critical?getConfig().getInt("discord.antidupe-color"):getConfig().getInt("discord.player-color"));
 }
 public void macroAlert(Player p,String action,double confidence,String signals){
   source(p,"macro-guard: padrão sustentado em "+action);
   String detail=id(p)+"\nAção monitorada: "+action+" | Confiança: "+Math.round(confidence*100)+"% | Sinais: "+signals
     +"\nPadrão estatístico sustentado (não é um contador simples de intervalo fixo). Isto é evidência para revisão manual, não um ban automático."
     +"\n"+loc(p);
   emitRaw("MACRO",p,"macro","🤖 PADRÃO DE MACRO/SCRIPT DETECTADO",detail,true,getConfig().getInt("discord.antidupe-color"));
 }
 public boolean onCommand(CommandSender s,Command c,String l,String[] a){if(!s.hasPermission("skybloodaudit.admin"))return true;reloadConfig();s.sendMessage("§aSkyBloodAudit v5 recarregado. SQLite: §f"+(db!=null?"OK":"OFF")+" §7| Economy: §f"+(eco==null?"nenhuma":eco.getName()));return true;}
}
