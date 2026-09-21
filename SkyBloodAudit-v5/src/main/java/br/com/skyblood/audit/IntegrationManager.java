package br.com.skyblood.audit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.plugin.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

public final class IntegrationManager {
 private final SkyBloodAudit core;
 private final Map<String,String> targets=new LinkedHashMap<>();
 IntegrationManager(SkyBloodAudit core){
  this.core=core;
  targets.put("EconomyShopGUI","EconomyShopGUI");
  targets.put("ExcellentCrates","ExcellentCrates");
  targets.put("RoseStacker","RoseStacker");
  targets.put("AxMinions","AxMinions");
  targets.put("SuperiorSkyblock2","SuperiorSkyblock2");
  targets.put("AmazingAuction","AmazingAuction");
 }
 public void load(){
  if(!core.getConfig().getBoolean("direct-integrations.enabled",true))return;
  checkVersions();
  hook("EconomyShopGUI","me.gypopo.economyshopgui.api.events.PostTransactionEvent", this::economyShop);
  hook("ExcellentCrates","su.nightexpress.excellentcrates.api.event.CrateObtainRewardEvent", this::crateReward);
  hook("RoseStacker","dev.rosewood.rosestacker.event.SpawnerStackEvent", this::roseSpawner);
  hook("RoseStacker","dev.rosewood.rosestacker.event.PreDropStackedItemsEvent", this::roseDrops);
  hook("AxMinions","com.artillexstudios.axminions.api.events.PreMinionPickupEvent", this::minionPickup);
  hook("AxMinions","com.artillexstudios.axminions.api.events.PreFarmerMinionHarvestEvent", this::minionGeneric);
  hook("AxMinions","com.artillexstudios.axminions.api.events.PreFisherMinionFishEvent", this::minionGeneric);
  hook("SuperiorSkyblock2","com.bgsoftware.superiorskyblock.api.events.IslandBankDepositEvent", e->skyBank(e,"depósito"));
  hook("SuperiorSkyblock2","com.bgsoftware.superiorskyblock.api.events.IslandBankWithdrawEvent", e->skyBank(e,"saque"));
  // AmazingAuction does not expose a Bukkit event API in the supplied JAR.
  // It stays on the command/GUI/Vault correlation adapter, deliberately fail-safe.
  Plugin aa=Bukkit.getPluginManager().getPlugin("AmazingAuction");
  if(aa!=null) core.integrationSystem("AmazingAuction","API pública de eventos não encontrada no JAR fornecido; usando correlação GUI/comando/Vault.",false);
 }
 private interface Sink{void accept(Event e)throws Exception;}
 @SuppressWarnings("unchecked")
 private void hook(String plugin,String clazz,Sink sink){
  if(!core.getConfig().getBoolean("direct-integrations."+plugin,true))return;
  Plugin p=Bukkit.getPluginManager().getPlugin(plugin);
  if(p==null){core.integrationSystem(plugin,"não instalado; adapter ignorado.",false);return;}
  try{
   Class<?> c=Class.forName(clazz,false,p.getClass().getClassLoader());
   if(!Event.class.isAssignableFrom(c))throw new IllegalStateException("classe não é Event");
   Bukkit.getPluginManager().registerEvent((Class<? extends Event>)c,new Listener(){},EventPriority.MONITOR,
    (listener,event)->{try{sink.accept(event);}catch(Throwable x){core.getLogger().warning(plugin+" adapter event: "+x.getMessage());}},
    core,true);
   core.integrationSystem(plugin,"integração direta ativa | versão "+p.getDescription().getVersion(),false);
  }catch(Throwable x){
   core.integrationSystem(plugin,"integração direta incompatível ("+x.getClass().getSimpleName()+"); fallback genérico continua ativo.",true);
  }
 }
 private Object call(Object o,String name)throws Exception{Method m=o.getClass().getMethod(name);return m.invoke(o);}
 private Player player(Object e)throws Exception{Object p=call(e,"getPlayer");if(p instanceof Player b)return b;try{Object b=call(p,"asPlayer");return b instanceof Player q?q:null;}catch(Exception ignored){return null;}}
 private void economyShop(Event e)throws Exception{
  Player p=player(e); if(p==null)return;
  Object result=call(e,"getTransactionResult"), type=call(e,"getTransactionType");
  int amount=((Number)call(e,"getAmount")).intValue();
  double price=((Number)call(e,"getPrice")).doubleValue();
  Object item=call(e,"getItemStack");
  core.integrationAlert(p,"EconomyShopGUI","transação "+type+" | resultado "+result+" | quantidade "+amount+" | preço "+price+" | item "+String.valueOf(item),false);
 }
 private void crateReward(Event e)throws Exception{
  Player p=player(e); if(p==null)return; Object reward=call(e,"getReward");
  String id=String.valueOf(call(reward,"getId")), name=String.valueOf(call(reward,"getName"));
  core.integrationAlert(p,"ExcellentCrates","recompensa confirmada | id "+id+" | nome "+name,false);
 }
 private void roseSpawner(Event e)throws Exception{
  Player p=player(e); if(p==null)return; int n=((Number)call(e,"getIncreaseAmount")).intValue();
  core.integrationAlert(p,"RoseStacker","stack de spawner confirmado | aumento "+n,false);
 }
 private void roseDrops(Event e)throws Exception{
  Object items=call(e,"getItems"), loc=call(e,"getLocation");
  core.integrationSystem("RoseStacker","drop de stack confirmado | itens "+items+" | local "+loc,false);
 }
 private void minionPickup(Event e)throws Exception{
  Player p=player(e);if(p!=null)core.integrationAlert(p,"AxMinions","coleta de minion confirmada",false);
 }
 private void minionGeneric(Event e)throws Exception{
  Player p=player(e);if(p!=null)core.integrationSource(p,"AxMinions",e.getEventName());
 }
 private void skyBank(Event e,String action)throws Exception{
  Player p=player(e);if(p==null)return;Object amount=call(e,"getAmount");
  core.integrationAlert(p,"SuperiorSkyblock2",action+" no banco da ilha | valor "+amount,false);
 }
 private void checkVersions(){
  try{
   File f=new File(core.getDataFolder(),core.getConfig().getString("compatibility.versions-file","integration-versions.yml"));
   YamlConfiguration y=YamlConfiguration.loadConfiguration(f); boolean changed=false;
   for(var en:targets.entrySet()){
    Plugin p=Bukkit.getPluginManager().getPlugin(en.getValue());if(p==null)continue;
    String now=p.getDescription().getVersion(),old=y.getString(en.getKey());
    if(old!=null&&!old.equals(now)&&core.getConfig().getBoolean("compatibility.notify-version-changes",true))
      core.integrationSystem(en.getKey(),"versão mudou: "+old+" -> "+now+". Adapter será testado automaticamente; fallback permanece disponível.",true);
    if(!Objects.equals(old,now)){y.set(en.getKey(),now);changed=true;}
   }
   if(changed){core.getDataFolder().mkdirs();y.save(f);}
  }catch(Exception x){core.getLogger().warning("Version registry: "+x.getMessage());}
 }
}
