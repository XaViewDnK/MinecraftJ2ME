import java.util.Enumeration;
import java.util.Vector;

/**
 * Хост сетевой игры — ServerSideNetworkHandler + ServerPlayer + ServerLevel 0.6.1. Клиенты (Android 0.6.1 и наш
 * клиент) получают мир по семени и запрашивают изменённые столбцы чанков; дальше хост рассылает блоки, сущности,
 * время, здоровье и т.д. Вызывается только из потока игры.
 */
public final class NetServer {
    final MC mc;
    final Raknet rak;
    final Level level;
    final Player player;
    /** все соединения (raknetInstance->send рассылает всем подключённым) */
    final Vector conns = new Vector();
    /** вошедшие, но ещё не прислали Ready (_pendingPlayers) */
    final Vector pending = new Vector();
    boolean allow;
    // что последним отправлено про игрока хоста (LocalPlayer::tick при isOnline)
    float sentX = -99999, sentY, sentZ, sentRotX, sentRotY;
    int sentItem = -1, sentAux = -1, sentArmor = -1, sentData = -1;
    long lastTimeSent = -1;

    NetServer(MC mc, Raknet rak, Level level, Player player) {
        this.mc = mc;
        this.rak = rak;
        this.level = level;
        this.player = player;
        if (player != null) player.owner = rak.guid;
    }

    /** ServerSideNetworkHandler::allowIncomingConnections: имя в ответе на пинг, пустое — скрыт. */
    public void allowIncomingConnections(boolean b) {
        allow = b;
        rak.setPong("MCCPP;Demo;" + (b ? Options.username : ""));
    }

    public boolean allowsIncomingConnections() {
        return allow;
    }

    public int clientCount() {
        return conns.size();
    }

    void shutdown() {
        level.net = null;
        for (int i = level.entities.size() - 1; i >= 0; i--) {
            Object o = level.entities.elementAt(i);
            if (o instanceof RemotePlayer) {
                ((Entity) o).remove();
                level.entities.removeElementAt(i);
            }
        }
    }

    // ---- отправка ----

    private void send(Raknet.Conn c, NetOut o) {
        rak.send(c, o.b, o.n, Raknet.RELIABLE_ORDERED);
    }

    /** RakPeer::Send(broadcast=true): всем, кроме except */
    private void broadcast(NetOut o, Raknet.Conn except, int rel) {
        for (int i = 0; i < conns.size(); i++) {
            Raknet.Conn c = (Raknet.Conn) conns.elementAt(i);
            if (c != except) rak.send(c, o.b, o.n, rel);
        }
    }

    private void broadcast(NetOut o) {
        broadcast(o, null, Raknet.RELIABLE_ORDERED);
    }

    private void broadcastRaw(byte[] d, Raknet.Conn except) {
        for (int i = 0; i < conns.size(); i++) {
            Raknet.Conn c = (Raknet.Conn) conns.elementAt(i);
            if (c != except) rak.send(c, d, d.length, Raknet.RELIABLE_ORDERED);
        }
    }

    private RemotePlayer playerOf(Raknet.Conn c) {
        for (int i = 0; i < level.entities.size(); i++) {
            Object o = level.entities.elementAt(i);
            if (o instanceof RemotePlayer && ((RemotePlayer) o).conn == c && !((Entity) o).removed) return (RemotePlayer) o;
        }
        return null;
    }

    private Entity entity(int id) {
        if (player != null && player.entityId == id) return player;
        return level.getEntity(id);
    }

    // ---- события сети ----

    void event(Raknet.Ev e) {
        switch (e.type) {
            case Raknet.EV_NEW_CLIENT:
                // ServerSideNetworkHandler::onNewClient
                if (!conns.contains(e.conn)) conns.addElement(e.conn);
                Log.i("NET new client " + e.conn.addr);
                return;
            case Raknet.EV_DISCONNECT:
                onDisconnect(e.conn);
                return;
            case Raknet.EV_PACKET:
                if (!conns.contains(e.conn)) conns.addElement(e.conn);
                packet(e.conn, e.data);
                return;
        }
    }

    /** ServerSideNetworkHandler::onDisconnect */
    private void onDisconnect(Raknet.Conn c) {
        conns.removeElement(c);
        for (int i = pending.size() - 1; i >= 0; i--) if (((RemotePlayer) pending.elementAt(i)).conn == c) pending.removeElementAt(i);
        RemotePlayer p = playerOf(c);
        if (p == null) return;
        displayGameMessage(p.name + " disconnected from the game");
        p.remove();
    }

    private void displayGameMessage(String s) {
        Net.message(mc, s);
        NetOut o = Net.out.reset();
        o.u8(Net.MESSAGE);
        o.str(s);
        broadcast(o);
    }

    private void packet(Raknet.Conn c, byte[] d) {
        NetIn in = new NetIn(d, 0, d.length);
        int id = in.u8();
        switch (id) {
            case Net.LOGIN: onLogin(c, in); break;
            case Net.READY: {
                int t = in.u8();
                if (t == Net.READY_CLIENTGENERATION) onReadyClientGeneration(c);
                if (t == Net.READY_REQUESTEDCHUNKS) onReadyRequestedChunks(c);
                break;
            }
            case Net.MOVEPLAYER: onMovePlayer(c, d, in); break;
            case Net.REMOVEBLOCK: onRemoveBlock(c, in); break;
            case Net.REQUESTCHUNK: onRequestChunk(c, in.i32(), in.i32()); break;
            case Net.PLAYEREQUIPMENT: onEquipment(c, d, in); break;
            case Net.PLAYERARMOREQUIPMENT: onArmor(c, d, in); break;
            case Net.INTERACT: onInteract(c, d, in); break;
            case Net.ANIMATE: onAnimate(c, d, in); break;
            case Net.USEITEM: onUseItem(c, in); break;
            case Net.ENTITYEVENT: onEntityEvent(c, in); break;
            case Net.PLAYERACTION: onPlayerAction(c, in); break;
            case Net.RESPAWN: onRespawn(c, d, in); break;
            case Net.SENDINVENTORY: onSendInventory(c, in); break;
            case Net.DROPITEM: onDropItem(c, in); break;
            case Net.CONTAINERSETSLOT: onContainerSetSlot(c, in); break;
            case Net.CONTAINERCLOSE: onContainerClose(c, in); break;
            case Net.SETHEALTH: onSetHealth(c, in); break;
            case Net.SIGNUPDATE: onSignUpdate(c, d, in); break;
        }
    }

    // ---- вход ----

    /** ServerSideNetworkHandler::handle(LoginPacket) */
    private void onLogin(Raknet.Conn c, NetIn in) {
        if (!allow) return;
        String name = in.str();
        int ver = -1, low = -1;
        if (in.remaining() >= 8) {
            ver = in.i32();
            low = in.i32();
        }
        int status = 0;
        if (ver < Net.PROTOCOL) status = 1;
        else if (low > Net.PROTOCOL) status = 2;
        NetOut o = Net.out.reset();
        o.u8(Net.LOGINSTATUS);
        o.i32(status);
        send(c, o);
        if (status != 0) return;
        RemotePlayer p = new RemotePlayer(level);
        p.conn = c;
        p.owner = c.guid;
        p.name = name;
        // Player::Player: над точкой появления мира; потом вверх, пока стоит в блоках
        float x = level.spawnX + 0.5f, y = level.spawnY + 1, z = level.spawnZ + 0.5f;
        p.setPos(x, y, z);
        while (p.y > 0 && p.y < Level.HEIGHT + 2 && !p.isFree(0, 0, 0)) p.setPos(p.x, p.y + 1, p.z);
        p.moveTo(p.x, p.y, p.z, 0, 0);
        pending.addElement(p);
        o = Net.out.reset();
        o.u8(Net.STARTGAME);
        o.i32((int) level.seed);
        o.i32(1);
        o.i32(level.gameType == 1 ? 1 : 0);
        o.i32(p.entityId);
        o.f32(p.x);
        o.f32(p.y);
        o.f32(p.z);
        send(c, o);
        Log.i("NET login " + name + " id " + p.entityId);
    }

    /** onReady_ClientGeneration: время, игроки, сущности, новый игрок — остальным. */
    private void onReadyClientGeneration(Raknet.Conn c) {
        RemotePlayer np = null;
        for (int i = 0; i < pending.size(); i++) {
            RemotePlayer p = (RemotePlayer) pending.elementAt(i);
            if (p.conn == c) {
                np = p;
                pending.removeElementAt(i);
                break;
            }
        }
        if (np == null) return;
        NetOut o = Net.out.reset();
        o.u8(Net.SETTIME);
        o.i32((int) level.time);
        send(c, o);
        // все уже играющие
        if (player != null) {
            writeAddPlayer(o.reset(), player);
            send(c, o);
            if (armorHash(player.armor) != 0) {
                writeArmor(o.reset(), player.entityId, player.armor);
                send(c, o);
            }
        }
        for (int i = 0; i < level.entities.size(); i++) {
            Object e = level.entities.elementAt(i);
            if (!(e instanceof RemotePlayer) || ((Entity) e).removed) continue;
            RemotePlayer rp = (RemotePlayer) e;
            writeAddPlayer(o.reset(), rp);
            send(c, o);
            if (armorHash(rp.armor) != 0) {
                writeArmor(o.reset(), rp.entityId, rp.armor);
                send(c, o);
            }
        }
        level.addEntity(np);
        Net.message(mc, np.name + " joined the game");
        for (int i = 0; i < level.entities.size(); i++) {
            Entity e = (Entity) level.entities.elementAt(i);
            if (e.removed || e instanceof RemotePlayer) continue;
            if (writeAdd(o.reset(), e)) send(c, o);
        }
        writeAddPlayer(o.reset(), np);
        broadcast(o, c, Raknet.RELIABLE_ORDERED);
    }

    /** onReady_RequestedChunks: все таблички (TileEntity::getUpdatePacket). */
    private void onReadyRequestedChunks(Raknet.Conn c) {
        for (Enumeration en = level.tileEntities.elements(); en.hasMoreElements(); ) {
            TileEntity te = (TileEntity) en.nextElement();
            if (te.text == null) continue;
            writeSign(Net.out.reset(), te);
            send(c, Net.out);
        }
    }

    /** RequestChunkPacket -> ChunkDataPacket: столбцы, отмеченные в updateMap, по 16 блоков + 8 байт data. */
    /** заводятся при первом запросе чанка клиентом (без клиентов — 48 КБ зря) */
    private byte[] chunkB, chunkD;

    private void onRequestChunk(Raknet.Conn c, int cx, int cz) {
        if (cx < 0 || cz < 0 || cx >= Level.CHUNKS || cz >= Level.CHUNKS) return;
        int ci = cx | (cz << 4);
        if (chunkB == null) {
            chunkB = new byte[32768];
            chunkD = new byte[16384];
        }
        byte[] blocks = chunkB, data = chunkD, um = level.um(ci);
        level.blocks.exportChunk(ci, blocks, false);
        level.data.exportChunk(ci, data, true);
        NetOut o = Net.out.reset();
        o.u8(Net.CHUNKDATA);
        o.i32(cx);
        o.i32(cz);
        for (int i = 0; i < 256; i++) {
            int bits = um[i] & 255;
            o.u8(bits);
            if (bits == 0) continue;
            int pos = ((i & 15) << 11) | ((i >> 4) << 7);
            for (int set = 0; set < 8; set++) {
                if ((bits & (1 << set)) == 0) continue;
                int p = pos + (set << 4);
                o.bytes(blocks, p, 16);
                o.bytes(data, p >> 1, 8);
            }
        }
        send(c, o);
        for (Enumeration en = level.tileEntities.elements(); en.hasMoreElements(); ) {
            TileEntity te = (TileEntity) en.nextElement();
            if (te.text == null || (te.x >> 4) != cx || (te.z >> 4) != cz) continue;
            writeSign(Net.out.reset(), te);
            send(c, Net.out);
        }
    }

    // ---- пакеты от клиентов ----

    private void onMovePlayer(Raknet.Conn c, byte[] d, NetIn in) {
        int id = in.i32();
        float x = in.f32(), y = in.f32(), z = in.f32(), yRot = in.f32(), xRot = in.f32();
        Entity e = entity(id);
        if (!(e instanceof RemotePlayer)) return;
        RemotePlayer p = (RemotePlayer) e;
        p.xd = p.yd = p.zd = 0;
        p.lerpTo(x, y, z, yRot, xRot);
        broadcastRaw(d, c);
    }

    /** handle(RemoveBlockPacket): как GameMode::destroyBlock, в выживании — выпадение. */
    private void onRemoveBlock(Raknet.Conn c, NetIn in) {
        RemotePlayer p = playerOf(c);
        if (p == null) return;
        in.i32();
        int x = in.i32(), z = in.i32(), y = in.u8();
        p.swing();
        int old = level.getTile(x, y, z), data = level.getData(x, y, z);
        if (old == 0) return;
        if (MC.instance != null) mc.particles.destroy(x, y, z, old, data);
        boolean changed = level.setTile(x, y, z, 0);
        if (changed) {
            mc.tileSound(Tile.breakSound(old), old, x, y, z, 0.5f);
            if (level.gameType == 0 && Item.canHarvest(p.heldId(), old)) TileLogic.playerDestroy(level, p.heldId(), old, x, y, z, data);
        }
    }

    private void onEquipment(Raknet.Conn c, byte[] d, NetIn in) {
        int id = in.i32(), item = in.u16(), aux = in.u16();
        Entity e = entity(id);
        if (!(e instanceof RemotePlayer) || ((RemotePlayer) e).conn != c) return;
        ((RemotePlayer) e).setHeld(item, aux);
        broadcastRaw(d, c);
    }

    private void onArmor(Raknet.Conn c, byte[] d, NetIn in) {
        int id = in.i32();
        Entity e = entity(id);
        if (!(e instanceof RemotePlayer) || ((RemotePlayer) e).conn != c) return;
        RemotePlayer p = (RemotePlayer) e;
        for (int i = 0; i < 4; i++) {
            int s = in.s8();
            p.armor[i] = s < 0 ? 0 : s + 256;
        }
        broadcastRaw(d, c);
    }

    /** handle(InteractPacket): удар — Player::attack по предмету в руке, иначе — ножницы/краска на овцу. */
    private void onInteract(Raknet.Conn c, byte[] d, NetIn in) {
        int action = in.u8(), src = in.i32(), target = in.i32();
        Entity s = entity(src), t = entity(target);
        if (!(s instanceof RemotePlayer) || t == null) return;
        RemotePlayer p = (RemotePlayer) s;
        p.swing();
        if (action == Net.INTERACT_ATTACK) {
            if (t instanceof Painting) t.hurt(1, p);
            else {
                int dmg = Item.attackDamage(p.heldId());
                if (dmg > 0) t.hurt(dmg, p);
            }
        } else if (action == Net.INTERACT_INTERACT) mc.interactWith(t, p.inv);
        broadcastRaw(d, c);
    }

    private void onAnimate(Raknet.Conn c, byte[] d, NetIn in) {
        int action = in.u8(), id = in.i32();
        if (player != null && id == player.entityId) return;
        Entity e = entity(id);
        if (!(e instanceof RemotePlayer)) return;
        if (action == Net.ANIM_SWING) ((RemotePlayer) e).swing();
        broadcastRaw(d, c);
    }

    /** handle(UseItemPacket): Tile::use, затем GameMode::useItem (грань 255) или useItemOn. */
    private void onUseItem(Raknet.Conn c, NetIn in) {
        int x = in.i32(), y = in.i32(), z = in.i32(), face = in.i32();
        int item = in.s16(), aux = in.u8();
        int id = in.i32();
        float cx = in.f32(), cy = in.f32(), cz = in.f32();
        Entity e = entity(id);
        if (!(e instanceof RemotePlayer)) return;
        RemotePlayer p = (RemotePlayer) e;
        if (face == 255) {
            if (item > 0) useItemRemote(p, item & 0xFFFF, aux, x / 32768f, y / 32768f, z / 32768f);
            return;
        }
        int t = level.getTile(x, y, z);
        if (t == Tile.INVISIBLE_BEDROCK) return;
        if (useTileRemote(p, x, y, z, t, item & 0xFFFF)) return;
        if (TileLogic.use(level, x, y, z, p)) return;
        if (item <= 0) return;
        int heldId = p.heldId(), heldAux = p.heldAux();
        p.setHeld(item & 0xFFFF, aux);
        mc.useItemOn(p, p.inv, item & 0xFFFF, aux, x, y, z, face, t, cy);
        p.setHeld(heldId, heldAux);
    }

    /** Окна блоков у игрока сервера: сундук/печь (ServerPlayer::openContainer), кровать, TNT от огнива. */
    private boolean useTileRemote(RemotePlayer p, int x, int y, int z, int id, int item) {
        switch (id) {
            case Tile.WORKBENCH:
            case Tile.STONECUTTER:
                return true;
            case Tile.CHEST:
            case Tile.FURNACE:
            case Tile.FURNACE_LIT: {
                if (id == Tile.CHEST && level.isSolidBlockingTile(x, y + 1, z)) return true;
                TileEntity te = level.getTileEntity(x, y, z);
                if (te == null) {
                    te = new TileEntity(id == Tile.CHEST ? "Chest" : "Furnace", x, y, z);
                    level.setTileEntity(te);
                }
                openContainer(p, te);
                return true;
            }
            case Tile.BED:
                useBed(p, x, y, z);
                return true;
            case Tile.TNT:
                if (item == 259) {
                    PrimedTnt t = new PrimedTnt(level, x + 0.5f, y + 0.01f, z + 0.5f);
                    level.addEntity(t);
                    mc.playSoundAt("random.fuse", t.x, t.y, t.z, 1f);
                    level.setTile(x, y, z, 0);
                    return true;
                }
                return false;
        }
        return false;
    }

    /** GameMode::useItem у игрока сервера: еда, лук (Player::startUsingItem), снежок/яйцо. */
    private void useItemRemote(RemotePlayer p, int item, int aux, float ax, float ay, float az) {
        if (item >= 256 && Item.food[item] > 0) {
            if (p.health < 20) {
                p.useItemId = item;
                p.useItemStart = p.tickCount;
            }
        } else if (item == 261) {
            p.useItemId = item;
            p.useItemStart = p.tickCount;
        } else if (item == 332 || item == 344) {
            mc.playSoundAt("random.bow", p.x, p.y, p.z, 0.5f);
            level.addEntity(ThrownItem.fromPlayer(level, p, item == 332 ? 81 : 82));
        }
    }

    /** Player::handleEntityEvent(USE_ITEM_COMPLETE) на сервере: доесть и ответить владельцу (ServerPlayer::completeUsingItem). */
    private void onEntityEvent(Raknet.Conn c, NetIn in) {
        int id = in.i32(), ev = in.u8();
        Entity e = entity(id);
        if (!(e instanceof RemotePlayer)) return;
        RemotePlayer p = (RemotePlayer) e;
        if (ev == Net.EV_USE_ITEM_COMPLETE) {
            NetOut o = Net.out.reset();
            o.u8(Net.ENTITYEVENT);
            o.i32(p.entityId);
            o.u8(Net.EV_USE_ITEM_COMPLETE);
            send(p.conn, o);
            if (p.useItemId >= 256 && Item.food[p.useItemId] > 0) {
                mc.playSoundAt("random.burp", p.x, p.y, p.z, 0.5f);
                p.heal(Item.food[p.useItemId]);
            }
            p.useItemId = 0;
        }
    }

    /** handle(PlayerActionPacket): лук стреляет (BowItem::releaseUsing), подъём с кровати. */
    private void onPlayerAction(Raknet.Conn c, NetIn in) {
        int action = in.i32();
        in.i32(); in.i32(); in.i32(); in.i32();
        int id = in.i32();
        Entity e = entity(id);
        if (!(e instanceof RemotePlayer)) return;
        RemotePlayer p = (RemotePlayer) e;
        if (action == Net.ACTION_RELEASE_USE_ITEM) {
            if (p.useItemId == 261) {
                float pow = (p.tickCount - p.useItemStart) / 20f;
                pow = (pow * pow + pow * 2) / 3;
                if (pow >= 0.1f) {
                    if (pow > 1) pow = 1;
                    mc.playSoundAt("random.bow", p.x, p.y, p.z, 1f);
                    Arrow a = Arrow.fromPlayer(level, p, pow * 2f);
                    a.crit = pow == 1;
                    level.addEntity(a);
                }
            }
            p.useItemId = 0;
        } else if (action == Net.ACTION_STOP_SLEEPING) {
            stopSleep(p, false);
        }
    }

    /** NetEventCallback::handle(RespawnPacket): moveTo (y — как прислали) и сброс игрока. */
    private void onRespawn(Raknet.Conn c, byte[] d, NetIn in) {
        int id = in.i32();
        float x = in.f32(), y = in.f32(), z = in.f32();
        Entity e = entity(id);
        if (e instanceof RemotePlayer) {
            RemotePlayer p = (RemotePlayer) e;
            p.moveTo(x, y, z, p.yRot, p.xRot);
            p.health = 20;
            p.deathTime = 0;
            p.hurtTime = 0;
            p.fire = 0;
            p.air = 300;
            p.sleeping = false;
            p.sentHealth = 20;
        }
        broadcastRaw(d, c);
    }

    /** handle(SendInventoryPacket): при смерти клиента его вещи выпадают у него (Inventory::dropAll). */
    private void onSendInventory(Raknet.Conn c, NetIn in) {
        int id = in.i32(), extra = in.u8(), num = in.s16();
        Entity e = entity(id);
        if (!(e instanceof RemotePlayer) || (extra & 1) == 0) return;
        for (int i = 0; i < num + 4 && in.remaining() >= 5; i++) {
            int item = in.u16(), count = in.u8(), aux = in.s16();
            if (i >= num) continue; // броню клиент выбрасывает сам (DropItemPacket)
            if (item > 0 && count > 0) drop((RemotePlayer) e, item, count, aux, true);
        }
    }

    private void onDropItem(Raknet.Conn c, NetIn in) {
        int id = in.i32(), type = in.u8(), item = in.u16(), count = in.u8(), aux = in.s16();
        Entity e = entity(id);
        if (!(e instanceof RemotePlayer) || item <= 0 || count <= 0) return;
        drop((RemotePlayer) e, item, count, aux, type != 0);
    }

    /** Player::drop: предмет из глаз вперёд (или во все стороны). */
    private void drop(RemotePlayer p, int item, int count, int aux, boolean randomly) {
        java.util.Random r = level.random;
        ItemEntity it = new ItemEntity(level, p.x, p.y + Player.EYE - 0.3f, p.z, item, count, aux);
        it.pickupDelay = 40;
        if (randomly) {
            float pow = r.nextFloat() * 0.5f, dir = r.nextFloat() * Mth.PI * 2;
            it.xd = -Mth.sin(dir) * pow;
            it.zd = Mth.cos(dir) * pow;
            it.yd = 0.2f;
        } else {
            float pow = 0.3f;
            it.xd = -Mth.sin(p.yRot * Mth.DEG) * Mth.cos(p.xRot * Mth.DEG) * pow;
            it.zd = Mth.cos(p.yRot * Mth.DEG) * Mth.cos(p.xRot * Mth.DEG) * pow;
            it.yd = -Mth.sin(p.xRot * Mth.DEG) * pow + 0.1f;
            float dir = r.nextFloat() * Mth.PI * 2;
            pow = 0.02f * r.nextFloat();
            it.xd += Mth.cos(dir) * pow;
            it.yd += (r.nextFloat() - r.nextFloat()) * 0.1f;
            it.zd += Mth.sin(dir) * pow;
        }
        level.addEntity(it);
    }

    /** handle(SetHealthPacket): <= -32 — урон от падения / лечение от клиента. */
    private void onSetHealth(Raknet.Conn c, NetIn in) {
        int h = in.s8();
        RemotePlayer p = playerOf(c);
        if (p == null || h > -32) return;
        int diff = h - Net.HEALTH_MODIFY_OFFSET;
        if (diff > 0) p.hurt(diff, null);
        else if (diff < 0) p.heal(-diff);
    }

    private void onSignUpdate(Raknet.Conn c, byte[] d, NetIn in) {
        broadcastRaw(d, c);
        int x = in.s16(), y = in.u8(), z = in.s16();
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = in.strLe();
            if (lines[i].length() > 15) lines[i] = lines[i].substring(0, 15);
        }
        TileEntity te = level.getTileEntity(x, y, z);
        if (te != null && te.text != null) {
            for (int i = 0; i < 4; i++) te.text[i] = lines[i];
            if (mc.signs != null) mc.signs.forget(level);
        }
    }

    // ---- контейнеры (ServerPlayer + ContainerMenu/FurnaceMenu) ----

    private void openContainer(RemotePlayer p, TileEntity te) {
        if (++p.containerCounter >= 100) p.containerCounter = 0;
        p.containerId = p.containerCounter;
        p.container = te;
        boolean furnace = te.isFurnace();
        NetOut o = Net.out.reset();
        o.u8(Net.CONTAINEROPEN);
        o.u8(p.containerId);
        o.u8(furnace ? Net.CT_FURNACE : Net.CT_CONTAINER);
        o.u8(te.id.length);
        o.str(furnace ? "Furnace" : "container.chest");
        send(p.conn, o);
        o = Net.out.reset();
        o.u8(Net.CONTAINERSETCONTENT);
        o.u8(p.containerId);
        o.u16(te.id.length);
        for (int i = 0; i < te.id.length; i++) {
            Net.writeItem(o, te.id[i], te.count[i], te.aux[i]);
            p.cId[i] = te.id[i];
            p.cCount[i] = te.count[i];
            p.cAux[i] = te.aux[i];
        }
        send(p.conn, o);
        p.cCook = p.cBurn = p.cLit = -1;
    }

    private void onContainerSetSlot(Raknet.Conn c, NetIn in) {
        int cid = in.u8(), slot = in.s16(), item = in.u16(), count = in.u8(), aux = in.s16();
        RemotePlayer p = playerOf(c);
        if (p == null || p.container == null || p.containerId != cid) return;
        TileEntity te = p.container;
        if (slot < 0 || slot >= te.id.length) return;
        if (item <= 0 || count <= 0) item = count = aux = 0;
        te.id[slot] = item;
        te.count[slot] = count;
        te.aux[slot] = aux;
        p.cId[slot] = item;
        p.cCount[slot] = count;
        p.cAux[slot] = aux;
    }

    private void onContainerClose(Raknet.Conn c, NetIn in) {
        RemotePlayer p = playerOf(c);
        if (p != null) p.container = null;
    }

    /** BaseContainerMenu::broadcastChanges: изменённые ячейки и данные печи. */
    private void containerChanges(RemotePlayer p) {
        TileEntity te = p.container;
        if (level.getTileEntity(te.x, te.y, te.z) != te) {
            NetOut o = Net.out.reset();
            o.u8(Net.CONTAINERCLOSE);
            o.u8(p.containerId);
            send(p.conn, o);
            p.container = null;
            return;
        }
        for (int i = 0; i < te.id.length; i++) {
            if (te.id[i] == p.cId[i] && te.count[i] == p.cCount[i] && te.aux[i] == p.cAux[i]) continue;
            p.cId[i] = te.id[i];
            p.cCount[i] = te.count[i];
            p.cAux[i] = te.aux[i];
            NetOut o = Net.out.reset();
            o.u8(Net.CONTAINERSETSLOT);
            o.u8(p.containerId);
            o.u16(i);
            Net.writeItem(o, te.id[i], te.count[i], te.aux[i]);
            send(p.conn, o);
        }
        if (te.isFurnace()) {
            if (te.cookTime != p.cCook) { p.cCook = te.cookTime; setData(p, 0, te.cookTime); }
            if (te.burnTime != p.cBurn) { p.cBurn = te.burnTime; setData(p, 1, te.burnTime); }
            if (te.litDuration != p.cLit) { p.cLit = te.litDuration; setData(p, 2, te.litDuration); }
        }
    }

    private void setData(RemotePlayer p, int id, int v) {
        NetOut o = Net.out.reset();
        o.u8(Net.CONTAINERSETDATA);
        o.u8(p.containerId);
        o.u16(id);
        o.u16(v);
        send(p.conn, o);
    }

    // ---- сон (Player::startSleepInBed на сервере, ServerLevel::updateSleepingPlayerList) ----

    private void useBed(RemotePlayer p, int x, int y, int z) {
        if (p.sleeping) {
            if (Math.abs(p.bedX - x) < 3 && Math.abs(p.bedY - y) < 3 && Math.abs(p.bedZ - z) < 3) stopSleep(p, false);
            return;
        }
        int data = level.getData(x, y, z);
        if ((data & 8) == 0) {
            int dir = data & 3;
            x += MC.HEAD_DIR[dir][0];
            z += MC.HEAD_DIR[dir][1];
            if (level.getTile(x, y, z) != Tile.BED) return;
        }
        if (p.health <= 0) return;
        if (Math.abs(p.x - x) > 3 || Math.abs(p.y + Player.EYE - y) > 4 || Math.abs(p.z - z) > 3) return;
        if (level.isDay()) {
            chat(p, "You can only sleep at night");
            return;
        }
        for (int i = 0; i < level.entities.size(); i++) {
            Object o = level.entities.elementAt(i);
            if (!(o instanceof Monster)) continue;
            Entity m = (Entity) o;
            if (Math.abs(m.x - x) < 8 && Math.abs(m.y - y) < 5 && Math.abs(m.z - z) < 8) {
                chat(p, "You may not rest now, there are monsters nearby");
                return;
            }
        }
        int dir = level.getData(x, y, z) & 3;
        float xo = 0.5f, zo = 0.5f;
        if (dir == 0) zo = 0.9f;
        else if (dir == 2) zo = 0.1f;
        else if (dir == 1) xo = 0.1f;
        else xo = 0.9f;
        p.moveTo(x + xo, y + 15f / 16f - 0.2f, z + zo, p.yRot, p.xRot);
        p.sleeping = true;
        p.sleepTimer = 0;
        p.bedX = x;
        p.bedY = y;
        p.bedZ = z;
        level.setData(x, y, z, level.getData(x, y, z) | 4);
    }

    void wakeUp(RemotePlayer p) {
        stopSleep(p, true);
    }

    /** ServerPlayer::stopSleepInBed: владельцу AnimatePacket(WAKE_UP). */
    private void stopSleep(RemotePlayer p, boolean forceful) {
        if (!p.sleeping) return;
        p.sleeping = false;
        p.sleepTimer = forceful ? 0 : Player.SLEEP_DURATION;
        if (level.getTile(p.bedX, p.bedY, p.bedZ) == Tile.BED)
            level.setData(p.bedX, p.bedY, p.bedZ, level.getData(p.bedX, p.bedY, p.bedZ) & ~4);
        NetOut o = Net.out.reset();
        o.u8(Net.ANIMATE);
        o.u8(Net.ANIM_WAKE_UP);
        o.i32(p.entityId);
        send(p.conn, o);
    }

    private void chat(RemotePlayer p, String s) {
        NetOut o = Net.out.reset();
        o.u8(Net.CHAT);
        o.str(s);
        send(p.conn, o);
    }

    /** ServerLevel::allPlayersSleeping: спят все и достаточно долго. */
    public boolean allPlayersSleeping() {
        if (player == null || !player.sleeping || player.sleepTimer < Player.SLEEP_DURATION) return false;
        for (int i = 0; i < level.entities.size(); i++) {
            Object o = level.entities.elementAt(i);
            if (!(o instanceof RemotePlayer) || ((Entity) o).removed) continue;
            RemotePlayer p = (RemotePlayer) o;
            if (!p.sleeping || p.sleepTimer < Player.SLEEP_DURATION) return false;
        }
        return true;
    }

    /** ServerLevel::tick: утро — время всем и awakenAllPlayers. */
    public void morning() {
        NetOut o = Net.out.reset();
        o.u8(Net.SETTIME);
        o.i32((int) level.time);
        broadcast(o);
        lastTimeSent = level.time;
        for (int i = 0; i < level.entities.size(); i++) {
            Object e = level.entities.elementAt(i);
            if (!(e instanceof RemotePlayer)) continue;
            RemotePlayer p = (RemotePlayer) e;
            stopSleep(p, false);
            p.health = p.lastHealth = 20;
        }
        o = Net.out.reset();
        o.u8(Net.SETHEALTH);
        o.u8(20);
        broadcast(o);
    }

    // ---- события мира (LevelListener) ----

    /** ServerSideNetworkHandler::tileChanged: UpdateBlockPacket всем. */
    public void tileChanged(int x, int y, int z) {
        if (conns.isEmpty()) return;
        NetOut o = Net.out.reset();
        o.u8(Net.UPDATEBLOCK);
        o.i32(x);
        o.i32(z);
        o.u8(y);
        o.u8(level.getTile(x, y, z));
        o.u8(level.getData(x, y, z));
        broadcast(o);
    }

    public void entityAdded(Entity e) {
        if (conns.isEmpty() || e instanceof RemotePlayer) return;
        NetOut o = Net.out.reset();
        if (writeAdd(o, e)) broadcast(o);
        e.netX = e.x; e.netY = e.y; e.netZ = e.z;
        e.netYRot = e.yRot; e.netXRot = e.xRot;
        e.netXd = e.xd; e.netYd = e.yd; e.netZd = e.zd;
        e.netHash = Net.dataHash(e);
    }

    public void entityRemoved(Entity e) {
        if (conns.isEmpty()) return;
        NetOut o = Net.out.reset();
        if (e instanceof RemotePlayer) {
            o.u8(Net.REMOVEPLAYER);
            o.i32(e.entityId);
            o.i64(((RemotePlayer) e).owner);
        } else {
            o.u8(Net.REMOVEENTITY);
            o.i32(e.entityId);
        }
        broadcast(o);
    }

    /** Level::broadcastEntityEvent */
    public void entityEvent(Entity e, int ev) {
        if (conns.isEmpty()) return;
        NetOut o = Net.out.reset();
        o.u8(Net.ENTITYEVENT);
        o.i32(e.entityId);
        o.u8(ev);
        broadcast(o);
    }

    /** ServerSideNetworkHandler::levelEvent (звук двери и т.п.) */
    public void levelEvent(int type, int x, int y, int z, int data) {
        if (conns.isEmpty()) return;
        NetOut o = Net.out.reset();
        o.u8(Net.LEVELEVENT);
        o.u16(type);
        o.u16(x);
        o.u16(y);
        o.u16(z);
        o.i32(data);
        broadcast(o);
    }

    /** Level::explode: ExplodePacket (блоки — смещения от точки взрыва). */
    public void explode(float x, float y, float z, float r, int[] blow, int nb) {
        if (conns.isEmpty()) return;
        NetOut o = Net.out.reset();
        o.u8(Net.EXPLODE);
        o.f32(x);
        o.f32(y);
        o.f32(z);
        o.f32(r);
        int xp = (int) x, yp = (int) y, zp = (int) z;
        o.i32(nb);
        for (int i = 0; i < nb; i++) {
            o.u8(blow[i * 3] - xp);
            o.u8(blow[i * 3 + 1] - yp);
            o.u8(blow[i * 3 + 2] - zp);
        }
        broadcast(o);
    }

    void hurtArmor(RemotePlayer p, int dmg) {
        NetOut o = Net.out.reset();
        o.u8(Net.HURTARMOR);
        o.u8(dmg);
        send(p.conn, o);
    }

    /** Свой игрок махнул рукой (LocalPlayer::swing): AnimatePacket, ненадёжно. */
    public void localSwing() {
        if (conns.isEmpty() || player == null) return;
        NetOut o = Net.out.reset();
        o.u8(Net.ANIMATE);
        o.u8(Net.ANIM_SWING);
        o.i32(player.entityId);
        broadcast(o, null, Raknet.UNRELIABLE);
    }

    /** Табличка отредактирована у хоста. */
    public void signChanged(TileEntity te) {
        if (conns.isEmpty()) return;
        writeSign(Net.out.reset(), te);
        broadcast(Net.out);
    }

    /** Свой игрок возродился (Minecraft::respawnPlayer). */
    public void localRespawn() {
        if (conns.isEmpty() || player == null) return;
        NetOut o = Net.out.reset();
        o.u8(Net.RESPAWN);
        o.i32(player.entityId);
        o.f32(player.x);
        o.f32(player.y + Player.EYE);
        o.f32(player.z);
        broadcast(o);
    }

    // ---- каждый тик ----

    void tick() {
        if (conns.isEmpty()) return;
        NetOut o = Net.out;
        // время: каждые 256 тиков (Level::tick)
        if ((level.time & 255) == 0 && level.time != lastTimeSent) {
            lastTimeSent = level.time;
            o.reset();
            o.u8(Net.SETTIME);
            o.i32((int) level.time);
            broadcast(o);
        }
        // свой игрок (LocalPlayer::tick)
        if (player != null) {
            Player p = player;
            if (Math.abs(p.x - sentX) > .1f || Math.abs(p.y - sentY) > .01f || Math.abs(p.z - sentZ) > .1f
                    || Math.abs(sentRotX - p.xRot) > 1 || Math.abs(sentRotY - p.yRot) > 1) {
                o.reset();
                o.u8(Net.MOVEPLAYER);
                o.i32(p.entityId);
                o.f32(p.x);
                o.f32(p.y);
                o.f32(p.z);
                o.f32(p.yRot);
                o.f32(p.xRot);
                broadcast(o);
                sentX = p.x; sentY = p.y; sentZ = p.z; sentRotX = p.xRot; sentRotY = p.yRot;
            }
            int item = p.inv.selCount() > 0 ? p.inv.selId() : 0, aux = p.inv.selCount() > 0 ? p.inv.selAux() : 0;
            if (item != sentItem || aux != sentAux) {
                sentItem = item;
                sentAux = aux;
                o.reset();
                o.u8(Net.PLAYEREQUIPMENT);
                o.i32(p.entityId);
                o.u16(item);
                o.u16(aux);
                broadcast(o);
            }
            int ah = armorHash(p.armor);
            if (ah != sentArmor) {
                sentArmor = ah;
                writeArmor(o.reset(), p.entityId, p.armor);
                broadcast(o);
            }
            int dh = Net.dataHash(p);
            if (dh != sentData) {
                sentData = dh;
                o.reset();
                o.u8(Net.SETENTITYDATA);
                o.i32(p.entityId);
                Net.packEntityData(o, p);
                broadcast(o);
            }
        }
        java.util.Vector v = level.entities;
        for (int i = 0; i < v.size(); i++) {
            Entity e = (Entity) v.elementAt(i);
            if (e.removed) continue;
            if (e instanceof RemotePlayer) {
                tickRemote((RemotePlayer) e);
                continue;
            }
            int dh = Net.dataHash(e);
            if (dh != e.netHash && (e instanceof Mob)) {
                e.netHash = dh;
                o.reset();
                o.u8(Net.SETENTITYDATA);
                o.i32(e.entityId);
                Net.packEntityData(o, e);
                broadcast(o);
            }
            if (!(e instanceof Mob)) continue;
            // Mob::tick (сервер): положение и скорость
            if (Math.abs(e.x - e.netX) > .1f || Math.abs(e.y - e.netY) > .05f || Math.abs(e.z - e.netZ) > .1f
                    || Math.abs(e.netXRot - e.xRot) > 1 || Math.abs(e.netYRot - e.yRot) > 1) {
                o.reset();
                o.u8(Net.MOVEENTITY_POSROT);
                o.i32(e.entityId);
                o.f32(e.x);
                o.f32(e.y);
                o.f32(e.z);
                o.f32(e.yRot);
                o.f32(e.xRot);
                broadcast(o);
                e.netX = e.x; e.netY = e.y; e.netZ = e.z; e.netXRot = e.xRot; e.netYRot = e.yRot;
            }
            float ddx = Math.abs(e.xd - e.netXd), ddy = Math.abs(e.yd - e.netYd), ddz = Math.abs(e.zd - e.netZd);
            float diff = ddx * ddx + ddy * ddy + ddz * ddz;
            if (diff > 0.02f * 0.02f || (diff > 0 && e.xd == 0 && e.yd == 0 && e.zd == 0)) {
                e.netXd = e.xd; e.netYd = e.yd; e.netZd = e.zd;
                o.reset();
                o.u8(Net.SETENTITYMOTION);
                o.i32(e.entityId);
                o.u16(motion(e.xd));
                o.u16(motion(e.yd));
                o.u16(motion(e.zd));
                broadcast(o);
            }
        }
    }

    /** ServerPlayer::tick: здоровье владельцу, изменения контейнера, подбор предметов и стрел (Player::touch). */
    private void tickRemote(RemotePlayer p) {
        NetOut o = Net.out;
        if (MC.instance.debug && (p.tickCount % 100) == 0)
            Log.i("NET rp " + p.name + " id " + p.entityId + " pos " + (int) p.x + "," + (int) p.y + "," + (int) p.z + " hp " + p.health + " dt " + p.deathTime + " sl " + p.sleeping + " held " + p.heldId());
        if (p.health != p.sentHealth) {
            p.sentHealth = p.health;
            o.reset();
            o.u8(Net.SETHEALTH);
            o.u8(p.health);
            send(p.conn, o);
        }
        int dh = Net.dataHash(p);
        if (dh != p.netHash) {
            p.netHash = dh;
            o.reset();
            o.u8(Net.SETENTITYDATA);
            o.i32(p.entityId);
            Net.packEntityData(o, p);
            broadcast(o);
        }
        if (p.container != null) containerChanges(p);
        if (p.health <= 0 || p.sleeping) return;
        java.util.Vector v = level.entities;
        float hw = p.bbW / 2 + 1;
        for (int i = 0; i < v.size(); i++) {
            Object ob = v.elementAt(i);
            if (ob instanceof ItemEntity) {
                ItemEntity e = (ItemEntity) ob;
                if (e.removed || e.pickupDelay > 0) continue;
                float w = hw + e.bbW / 2;
                if (Math.abs(e.x - p.x) < w && Math.abs(e.z - p.z) < w && e.y + e.bbH > p.y && e.y < p.y + p.bbH) {
                    take(e, p);
                    mc.playSoundAt("random.pop", e.x, e.y, e.z, 0.2f);
                    e.remove();
                }
            } else if (ob instanceof Arrow) {
                Arrow a = (Arrow) ob;
                if (a.removed || !a.inGround || !a.playerArrow || a.shakeTime > 0) continue;
                float w = hw + a.bbW / 2;
                if (Math.abs(a.x - p.x) < w && Math.abs(a.z - p.z) < w && a.y + a.bbH > p.y && a.y < p.y + p.bbH) {
                    take(a, p);
                    mc.playSoundAt("random.pop", a.x, a.y, a.z, 0.2f);
                    a.remove();
                }
            }
        }
    }

    /** ServerPlayer::take: TakeItemEntityPacket всем */
    private void take(Entity e, RemotePlayer p) {
        NetOut o = Net.out.reset();
        o.u8(Net.TAKEITEMENTITY);
        o.i32(e.entityId);
        o.i32(p.entityId);
        broadcast(o);
    }

    static int motion(float v) {
        if (v < -3.9f) v = -3.9f;
        if (v > 3.9f) v = 3.9f;
        return (int) (8000f * v) & 0xFFFF;
    }

    // ---- построение пакетов ----

    static int armorHash(int[] a) {
        return (a[0] & 255) | ((a[1] & 255) << 8) | ((a[2] & 255) << 16) | ((a[3] & 255) << 24) | (a[0] != 0 || a[1] != 0 || a[2] != 0 || a[3] != 0 ? 1 : 0);
    }

    static void writeArmor(NetOut o, int id, int[] armor) {
        o.u8(Net.PLAYERARMOREQUIPMENT);
        o.i32(id);
        for (int i = 0; i < 4; i++) o.u8(Item.isArmor(armor[i]) ? armor[i] - 256 : -1);
    }

    void writeAddPlayer(NetOut o, Entity e) {
        o.u8(Net.ADDPLAYER);
        String name;
        long owner;
        int item, aux;
        if (e instanceof Player) {
            Player p = (Player) e;
            name = Options.username;
            owner = rak.guid;
            item = p.inv.selCount() > 0 ? p.inv.selId() : 0;
            aux = p.inv.selCount() > 0 ? p.inv.selAux() : 0;
        } else {
            RemotePlayer p = (RemotePlayer) e;
            name = p.name;
            owner = p.owner;
            item = p.heldId();
            aux = p.heldAux();
        }
        o.i64(owner);
        o.str(name);
        o.i32(e.entityId);
        o.f32(e.x);
        o.f32(e.y);
        o.f32(e.z);
        o.u8(Net.rotToByte(e.yRot));
        o.u8(Net.rotToByte(e.xRot));
        o.u16(item);
        o.u16(aux);
        Net.packEntityData(o, e);
    }

    /** ServerSideNetworkHandler::getAddPacketFromEntity */
    static boolean writeAdd(NetOut o, Entity e) {
        if (e instanceof RemotePlayer || e instanceof Player) return false;
        if (e instanceof Mob) {
            o.u8(Net.ADDMOB);
            o.i32(e.entityId);
            o.i32(e.typeId());
            o.f32(e.x);
            o.f32(e.y);
            o.f32(e.z);
            o.u8(Net.rotToByte(e.yRot));
            o.u8(Net.rotToByte(e.xRot));
            Net.packEntityData(o, e);
            return true;
        }
        if (e instanceof ItemEntity) {
            ItemEntity it = (ItemEntity) e;
            o.u8(Net.ADDITEMENTITY);
            o.i32(e.entityId);
            o.u16(it.item);
            o.u8(it.count);
            o.u16(it.aux);
            o.f32(e.x);
            o.f32(e.y + e.bbH / 2);
            o.f32(e.z);
            int xa = (int) (e.xd * 128.0), ya = (int) (e.yd * 128.0), za = (int) (e.zd * 128.0);
            o.u8(xa);
            o.u8(ya);
            o.u8(za);
            // сервер берёт себе ту же округлённую скорость, что увидят клиенты
            e.xd = (byte) xa / 128f;
            e.yd = (byte) ya / 128f;
            e.zd = (byte) za / 128f;
            return true;
        }
        if (e instanceof Painting) {
            Painting p = (Painting) e;
            o.u8(Net.ADDPAINTING);
            o.i32(e.entityId);
            o.i32(p.xTile);
            o.i32(p.yTile);
            o.i32(p.zTile);
            o.i32(p.dir);
            o.str(Painting.NAME[p.motive]);
            return true;
        }
        int data = 0;
        if (e instanceof Arrow) data = ((Arrow) e).owner != null ? ((Arrow) e).owner.entityId : 0;
        else if (e instanceof ThrownItem) data = ((ThrownItem) e).owner != null ? ((ThrownItem) e).owner.entityId : 0;
        else if (e instanceof FallingTile) data = -(((FallingTile) e).tile | (((FallingTile) e).data << 16));
        o.u8(Net.ADDENTITY);
        o.i32(e.entityId);
        o.u8(e.typeId());
        o.f32(e.x);
        o.f32(Level.origY(e));
        o.f32(e.z);
        o.i32(data);
        if (data > 0) {
            o.u16(motion(e.xd));
            o.u16(motion(e.yd));
            o.u16(motion(e.zd));
        }
        return true;
    }

    static void writeSign(NetOut o, TileEntity te) {
        o.u8(Net.SIGNUPDATE);
        o.u16(te.x);
        o.u8(te.y);
        o.u16(te.z);
        for (int i = 0; i < 4; i++) o.strLe(te.text[i]);
    }
}
