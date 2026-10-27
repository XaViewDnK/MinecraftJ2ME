import java.util.Vector;

/**
 * Клиент сетевой игры — ClientSideNetworkHandler + MultiPlayerLevel 0.6.1: поиск игр (пинг), вход, мир по семени
 * хоста, запрос всех 256 чанков по одному (приходят только изменённые столбцы), дальше — блоки, сущности, игроки,
 * время и здоровье от хоста. Свои действия игрок делает сразу у себя и сообщает хосту пакетами.
 */
public final class NetClient {
    final MC mc;
    final Raknet rak;
    public Raknet.Conn server;
    public Level level;
    /** найденные игры: {адрес, имя, guid, время ответа} */
    public final Vector games = new Vector();
    public static final class Game {
        public String addr, name;
        public long guid, seen;
    }
    // состояние входа
    public static final int S_SEARCH = 0, S_CONNECTING = 1, S_LOGIN = 2, S_GENERATING = 3, S_CHUNKS = 4, S_PLAYING = 5, S_FAILED = 6;
    public int state;
    public String failMessage;
    boolean loggedIn;
    // StartGame
    long seed;
    int gameType, myId;
    float sx, sy, sz;
    // чанки (requestNextChunk)
    final int[] order = new int[256];
    int nextChunk, chunksDone;
    final boolean[] loaded = new boolean[256];
    final Vector buffered = new Vector(); // int[]{x,y,z,id,data}
    // свой игрок (LocalPlayer::tick)
    float sentX = -99999, sentY, sentZ, sentRotX, sentRotY;
    int sentItem = -1, sentAux = -1, sentArmor = -1;
    boolean wasSleepFlag;
    int bedX, bedY, bedZ;
    // открытый контейнер
    TileEntity container;
    int containerId = -1;
    final int[] cId = new int[27], cCount = new int[27], cAux = new int[27];

    NetClient(MC mc, Raknet rak) {
        this.mc = mc;
        this.rak = rak;
    }

    void shutdown() {
        if (server != null) rak.close(server, true);
    }

    // ---- поиск и подключение ----

    /** Minecraft::joinMultiplayer */
    public void join(String addr) {
        rak.stopPing();
        state = S_CONNECTING;
        rak.connect(Raknet.hostOf(addr), Raknet.portOf(addr));
    }

    private void fail(String msg) {
        state = S_FAILED;
        failMessage = msg;
    }

    void event(Raknet.Ev e) {
        switch (e.type) {
            case Raknet.EV_PONG:
                pong(e);
                return;
            case Raknet.EV_CONNECTED:
                onConnect(e.conn);
                return;
            case Raknet.EV_FAILED:
                if (state == S_CONNECTING) fail("Could not connect to server");
                return;
            case Raknet.EV_DISCONNECT:
                onDisconnect();
                return;
            case Raknet.EV_PACKET:
                if (e.conn == server) packet(e.data);
                return;
        }
    }

    /** RakNetInstance::handleUnconnectedPong: "MCCPP;Demo;" + имя (MINECON — в начало списка). */
    private void pong(Raknet.Ev e) {
        String id1 = "MCCPP;Demo;", id2 = "MCCPP;MINECON;";
        String name;
        if (e.name.startsWith(id1)) name = e.name.substring(id1.length());
        else if (e.name.startsWith(id2)) name = e.name.substring(id2.length());
        else return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < games.size(); i++) {
            Game g = (Game) games.elementAt(i);
            if (g.guid == e.guid) {
                // тот же хост ответил и по широковещанию, и через 127.0.0.1 — оставляем адрес сети
                if (Raknet.hostOf(g.addr).startsWith("127.") && !Raknet.hostOf(e.addr).startsWith("127.")) g.addr = e.addr;
                g.name = name;
                g.seen = now;
                return;
            }
        }
        Game g = new Game();
        g.addr = e.addr;
        g.name = name;
        g.guid = e.guid;
        g.seen = now;
        games.addElement(g);
    }

    /** ClientSideNetworkHandler::onConnect: LoginPacket(имя, 9). */
    private void onConnect(Raknet.Conn c) {
        server = c;
        state = S_LOGIN;
        NetOut o = Net.out.reset();
        o.u8(Net.LOGIN);
        o.str(Options.username);
        o.i32(Net.PROTOCOL);
        o.i32(Net.PROTOCOL);
        send(o);
    }

    private void onDisconnect() {
        if (state < S_PLAYING) {
            fail("Disconnected from server");
            return;
        }
        // ClientSideNetworkHandler::onDisconnect: чужие игроки исчезают, мир становится обычным
        if (level != null) {
            for (int i = level.entities.size() - 1; i >= 0; i--) {
                Entity e = (Entity) level.entities.elementAt(i);
                if (e instanceof RemotePlayer) {
                    e.remove();
                    level.entities.removeElementAt(i);
                }
            }
        }
        Net.message(mc, "Disconnected from server");
        server = null;
    }

    private void send(NetOut o) {
        if (server != null) rak.send(server, o.b, o.n, Raknet.RELIABLE_ORDERED);
    }

    private void sendUnreliable(NetOut o) {
        if (server != null) rak.send(server, o.b, o.n, Raknet.UNRELIABLE);
    }

    void tick() {
        // список игр: убрать молчащие больше 3 с (RakNetInstance::runEvents)
        if (state == S_SEARCH) {
            long now = System.currentTimeMillis();
            for (int i = games.size() - 1; i >= 0; i--) if (now - ((Game) games.elementAt(i)).seen > 3000) games.removeElementAt(i);
            return;
        }
        if (state != S_PLAYING && state != S_CHUNKS) return;
        if (level == null || mc.player == null || server == null) return;
        Player p = mc.player;
        NetOut o = Net.out;
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
            send(o);
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
            send(o);
        }
        int ah = NetServer.armorHash(p.armor);
        if (ah != sentArmor) {
            sentArmor = ah;
            NetServer.writeArmor(o.reset(), p.entityId, p.armor);
            send(o);
        }
        if (container != null) containerChanges();
    }

    // ---- пакеты хоста ----

    private void packet(byte[] d) {
        NetIn in = new NetIn(d, 0, d.length);
        int id = in.u8();
        // RakNetInstance::runEvents: до входа — только статусные пакеты (до READY)
        if (!loggedIn && id > Net.READY) {
            if (id != Net.LOGINSTATUS) return;
        }
        switch (id) {
            case Net.LOGINSTATUS: onLoginStatus(in.i32()); break;
            case Net.STARTGAME: onStartGame(in); break;
            case Net.MESSAGE: Net.message(mc, in.str()); break;
            case Net.CHAT: Net.message(mc, in.str()); break;
            case Net.SETTIME: if (level != null) { level.time = in.i32() & 0xFFFFFFFFL; level.updateSkyDarken(); } break;
            case Net.ADDMOB: onAddMob(in); break;
            case Net.ADDPLAYER: onAddPlayer(in); break;
            case Net.REMOVEPLAYER: onRemove(in.i32()); break;
            case Net.REMOVEENTITY: onRemove(in.i32()); break;
            case Net.ADDENTITY: onAddEntity(in); break;
            case Net.ADDITEMENTITY: onAddItem(in); break;
            case Net.ADDPAINTING: onAddPainting(in); break;
            case Net.TAKEITEMENTITY: onTake(in.i32(), in.i32()); break;
            case Net.MOVEPLAYER:
            case Net.MOVEENTITY_POSROT: onMove(in); break;
            case Net.UPDATEBLOCK: onUpdateBlock(in); break;
            case Net.EXPLODE: onExplode(in); break;
            case Net.LEVELEVENT: onLevelEvent(in); break;
            case Net.ENTITYEVENT: onEntityEvent(in.i32(), in.u8()); break;
            case Net.CHUNKDATA: onChunkData(in); break;
            case Net.PLAYEREQUIPMENT: onEquipment(in); break;
            case Net.PLAYERARMOREQUIPMENT: onArmor(in); break;
            case Net.SETENTITYDATA: onEntityData(in); break;
            case Net.SETENTITYMOTION: onMotion(in); break;
            case Net.SETHEALTH: onSetHealth(in.s8()); break;
            case Net.SETSPAWNPOSITION: onSpawnPos(in); break;
            case Net.ANIMATE: onAnimate(in.u8(), in.i32()); break;
            case Net.RESPAWN: onRespawn(in); break;
            case Net.HURTARMOR: if (mc.player != null) mc.player.hurtArmorNet(in.s8()); break;
            case Net.CONTAINEROPEN: onContainerOpen(in); break;
            case Net.CONTAINERCLOSE: onContainerClose(); break;
            case Net.CONTAINERSETSLOT: onContainerSetSlot(in); break;
            case Net.CONTAINERSETDATA: onContainerSetData(in); break;
            case Net.CONTAINERSETCONTENT: onContainerSetContent(in); break;
            case Net.SIGNUPDATE: onSignUpdate(in); break;
            case Net.ADVENTURESETTINGS: if (level != null) level.showNameTags = (in.i32() & 32) != 0; break;
        }
    }

    private void onLoginStatus(int status) {
        if (status == 0) {
            loggedIn = true;
            return;
        }
        fail(status == 1 ? "Could not connect: Outdated client!" : "Could not connect: Outdated server!");
        rak.close(server, true);
        server = null;
    }

    /** handle(StartGamePacket): временный мир по семени хоста (MultiPlayerLevel, isClientSide). */
    private void onStartGame(NetIn in) {
        seed = in.i32();
        in.i32();
        gameType = in.i32();
        myId = in.i32();
        sx = in.f32();
        sy = in.f32();
        sz = in.f32();
        state = S_GENERATING;
        mc.netStartGame(this, seed, gameType == 1 ? 1 : 0);
    }

    /** MC построил мир: ClientSideNetworkHandler::levelGenerated — Ready и первый запрос чанка. */
    void levelGenerated(Level l) {
        level = l;
        Player p = mc.player;
        p.entityId = myId;
        p.owner = rak.guid;
        p.setPos(sx, sy, sz);
        p.resetPosNet();
        NetOut o = Net.out.reset();
        o.u8(Net.READY);
        o.u8(Net.READY_CLIENTGENERATION);
        send(o);
        // arrangeRequestChunkOrder: ближние к игроку — первыми
        int cx = Mth.floor(p.x / 16), cz = Mth.floor(p.z / 16);
        for (int i = 0; i < 256; i++) {
            order[i] = i;
            loaded[i] = false;
        }
        int[] dist = new int[256];
        for (int i = 0; i < 256; i++) {
            int ax = i / 16 - cx, az = i % 16 - cz;
            dist[i] = ax * ax + az * az;
        }
        for (int i = 1; i < 256; i++) {
            int v = order[i], j = i - 1;
            while (j >= 0 && dist[order[j]] > dist[v]) { order[j + 1] = order[j]; j--; }
            order[j + 1] = v;
        }
        nextChunk = 0;
        chunksDone = 0;
        state = S_CHUNKS;
        requestNextChunk();
    }

    private void requestNextChunk() {
        if (nextChunk >= 256) return;
        int i = order[nextChunk++];
        NetOut o = Net.out.reset();
        o.u8(Net.REQUESTCHUNK);
        o.i32(i / 16);
        o.i32(i % 16);
        send(o);
    }

    private final byte[] chunkB = new byte[32768], chunkD = new byte[16384];

    /** handle(ChunkDataPacket): изменённые столбцы поверх своей генерации. */
    private void onChunkData(NetIn in) {
        if (level == null) return;
        int cx = in.i32(), cz = in.i32();
        if (cx < 0 || cz < 0 || cx >= 16 || cz >= 16) return;
        int ci = cx | (cz << 4);
        byte[] blocks = chunkB, data = chunkD;
        level.blocks.exportChunk(ci, blocks, false);
        level.data.exportChunk(ci, data, true);
        boolean any = false;
        for (int i = 0; i < 256; i++) {
            int bits = in.u8();
            if (bits == 0) continue;
            any = true;
            int x = i & 15, z = i >> 4;
            int pos = (x << 11) | (z << 7);
            for (int set = 0; set < 8; set++) {
                if ((bits & (1 << set)) == 0) continue;
                int p = pos + (set << 4);
                for (int k = 0; k < 16; k++) {
                    int b = in.u8();
                    blocks[p + k] = (byte) validBlock(b, cx * 16 + x, (set << 4) + k, cz * 16 + z);
                }
                for (int k = 0; k < 8; k++) data[(p >> 1) + k] = (byte) in.u8();
            }
            if (bits != 0) level.umw(ci)[i] |= (byte) bits;
        }
        if (any) {
            level.blocks.importChunk(ci, blocks, false);
            level.data.importChunk(ci, data, true);
        }
        if (any) mc.netChunkChanged(cx, cz, state == S_PLAYING);
        loaded[cx * 16 + cz] = true;
        chunksDone++;
        if (chunksDone >= 256 || nextChunk >= 256) {
            if (state == S_CHUNKS) allChunksLoaded();
        } else requestNextChunk();
    }

    /** Все чанки есть: Ready(2), отложенные блоки, вход в игру. */
    private void allChunksLoaded() {
        NetOut o = Net.out.reset();
        o.u8(Net.READY);
        o.u8(Net.READY_REQUESTEDCHUNKS);
        send(o);
        state = S_PLAYING;
        mc.netEnterGame(this);
        for (int i = 0; i < buffered.size(); i++) {
            int[] u = (int[]) buffered.elementAt(i);
            level.setTileAndData(u[0], u[1], u[2], validBlock(u[3], u[0], u[1], u[2]), u[4]);
        }
        buffered.removeAllElements();
    }

    /** Tile::transformToValidBlockId: неизвестный блок — «update game» */
    static int validBlock(int id, int x, int y, int z) {
        if (id != 0 && !Tile.exists[id]) return ((x + y + z) & 1) == 1 ? 248 : 249;
        return id;
    }

    private void onUpdateBlock(NetIn in) {
        if (level == null) return;
        int x = in.i32(), z = in.i32(), y = in.u8(), id = in.u8(), data = in.u8();
        if (x < 0 || z < 0 || x >= 256 || z >= 256) return;
        if (loaded[(x >> 4) * 16 + (z >> 4)] && state == S_PLAYING) level.setTileAndData(x, y, z, validBlock(id, x, y, z), data);
        else buffered.addElement(new int[] { x, y, z, id, data });
    }

    // ---- сущности ----

    private void add(Entity e, int id) {
        if (level == null) return;
        Entity old = level.getEntity(id);
        if (old != null) {
            old.remove();
            level.entities.removeElement(old);
        }
        e.entityId = id;
        Level.netAdd = true;
        try {
            level.addEntity(e);
        } finally {
            Level.netAdd = false;
        }
    }

    private void onAddMob(NetIn in) {
        if (level == null) return;
        int id = in.i32(), type = in.i32();
        float x = in.f32(), y = in.f32(), z = in.f32();
        float yRot = Net.byteToRot(in.u8()), xRot = Net.byteToRot(in.u8());
        if (type == 0) return;
        Entity e = Mobs.create(type, level);
        if (!(e instanceof Mob)) return;
        Mob m = (Mob) e;
        m.setPos(x, y, z);
        m.yRot = m.yRotO = m.bodyRot = m.bodyRotO = yRot;
        m.xRot = m.xRotO = xRot;
        Net.readEntityData(in, m);
        add(m, id);
    }

    private void onAddPlayer(NetIn in) {
        if (level == null) return;
        long owner = in.i64();
        String name = in.str();
        int id = in.i32();
        float x = in.f32(), y = in.f32(), z = in.f32();
        float yRot = Net.byteToRot(in.u8()), xRot = Net.byteToRot(in.u8());
        int item = in.s16(), aux = in.s16();
        if (id == myId) return;
        RemotePlayer p = new RemotePlayer(level);
        p.owner = owner;
        p.name = name;
        p.moveTo(x, y, z, yRot, xRot);
        Net.readEntityData(in, p);
        p.setHeld(item & 0xFFFF, aux & 0xFFFF);
        add(p, id);
        Net.message(mc, name + " joined the game");
    }

    private void onRemove(int id) {
        if (level == null) return;
        Entity e = level.getEntity(id);
        if (e == null) return;
        e.remove();
        level.entities.removeElement(e);
    }

    /** handle(AddEntityPacket): EntityFactory::CreateEntity + падающий блок / стрела. */
    private void onAddEntity(NetIn in) {
        if (level == null) return;
        int id = in.i32(), type = in.u8();
        float x = in.f32(), y = in.f32(), z = in.f32();
        int data = in.i32();
        Entity e = Mobs.create(type, level);
        if (e == null || e instanceof Mob) return;
        e.setPos(x, y - e.savedYOffsetPublic(), z);
        if (e instanceof FallingTile) {
            int dd = -data;
            ((FallingTile) e).tile = dd & 0xFF;
            ((FallingTile) e).data = dd >> 16;
        }
        if (data > 0) {
            Entity owner = level.getEntity(data);
            if (owner == null && mc.player != null && mc.player.entityId == data) owner = mc.player;
            if (e instanceof Arrow) ((Arrow) e).owner = owner;
            if (e instanceof ThrownItem) ((ThrownItem) e).owner = owner;
            e.xd = in.s16() / 8000f;
            e.yd = in.s16() / 8000f;
            e.zd = in.s16() / 8000f;
        }
        if (e instanceof PrimedTnt) e.xd = e.yd = e.zd = 0;
        add(e, id);
    }

    private void onAddItem(NetIn in) {
        if (level == null) return;
        int id = in.i32(), item = in.s16(), count = in.u8(), aux = in.s16();
        float x = in.f32(), y = in.f32(), z = in.f32();
        int xa = in.s8(), ya = in.s8(), za = in.s8();
        ItemEntity e = new ItemEntity(level, x, y, z, item & 0xFFFF, count, aux & 0xFFFF);
        e.xd = xa / 128f;
        e.yd = ya / 128f;
        e.zd = za / 128f;
        add(e, id);
    }

    private void onAddPainting(NetIn in) {
        if (level == null) return;
        int id = in.i32(), xt = in.i32(), yt = in.i32(), zt = in.i32(), dir = in.i32();
        String motive = in.str();
        Painting p = new Painting(level);
        p.xTile = xt;
        p.yTile = yt;
        p.zTile = zt;
        p.motive = 0;
        for (int i = 0; i < Painting.NAME.length; i++) if (Painting.NAME[i].equals(motive)) p.motive = i;
        p.setDir(dir);
        add(p, id);
    }

    /** handle(TakeItemEntityPacket): анимация подбора, предмет — себе, не влез — обратно хосту. */
    private void onTake(int itemId, int playerId) {
        if (level == null) return;
        Entity e = level.getEntity(itemId);
        if (e == null) return;
        int item = 0, count = 0, aux = 0;
        if (e instanceof ItemEntity) {
            ItemEntity it = (ItemEntity) e;
            item = it.item;
            count = it.count;
            aux = it.aux;
            mc.takeAnim(it);
        } else if (e instanceof Arrow) {
            item = 262;
            count = 1;
        }
        if (item == 0) return;
        Player p = mc.player;
        if (p != null && p.entityId == playerId) {
            int left = p.inv.add(item, count, aux);
            if (left > 0) {
                NetOut o = Net.out.reset();
                o.u8(Net.DROPITEM);
                o.i32(playerId);
                o.u8(0);
                Net.writeItem(o, item, left, aux);
                send(o);
            }
        }
        mc.playSoundAt("random.pop", e.x, e.y, e.z, 0.2f);
    }

    private void onMove(NetIn in) {
        if (level == null) return;
        int id = in.i32();
        float x = in.f32(), y = in.f32(), z = in.f32(), yRot = in.f32(), xRot = in.f32();
        Entity e = level.getEntity(id);
        if (e instanceof RemotePlayer) ((RemotePlayer) e).lerpTo(x, y, z, yRot, xRot);
        else if (e instanceof Mob) ((Mob) e).lerpTo(x, y, z, yRot, xRot);
        else if (e != null) {
            e.setPos(x, y, z);
            e.yRot = yRot;
            e.xRot = xRot;
        }
    }

    private void onMotion(NetIn in) {
        if (level == null) return;
        int id = in.i32();
        float xd = in.s16() / 8000f, yd = in.s16() / 8000f, zd = in.s16() / 8000f;
        Entity e = level.getEntity(id);
        if (e == null) return;
        e.xd = xd;
        e.yd = yd;
        e.zd = zd;
    }

    private void onEntityData(NetIn in) {
        if (level == null) return;
        int id = in.i32();
        Entity e = level.getEntity(id);
        if (e == null && mc.player != null && id == mc.player.entityId) e = mc.player;
        Net.readEntityData(in, e);
    }

    private void onEntityEvent(int id, int ev) {
        if (level == null) return;
        Player p = mc.player;
        if (p != null && id == p.entityId) {
            // ServerPlayer::completeUsingItem: только после ответа хоста доедаем (LocalPlayer)
            if (ev == Net.EV_USE_ITEM_COMPLETE) p.completeUsingItemNet();
            return;
        }
        Entity e = level.getEntity(id);
        if (e instanceof Mob) ((Mob) e).netEvent(ev);
    }

    private void onEquipment(NetIn in) {
        if (level == null) return;
        int id = in.i32(), item = in.u16(), aux = in.u16();
        Entity e = level.getEntity(id);
        if (e instanceof RemotePlayer) ((RemotePlayer) e).setHeld(item, aux);
    }

    private void onArmor(NetIn in) {
        if (level == null) return;
        int id = in.i32();
        Entity e = level.getEntity(id);
        if (!(e instanceof RemotePlayer)) return;
        RemotePlayer p = (RemotePlayer) e;
        for (int i = 0; i < 4; i++) {
            int s = in.s8();
            p.armor[i] = s < 0 ? 0 : s + 256;
        }
    }

    /** LocalPlayer::hurtTo */
    private void onSetHealth(int h) {
        if (level == null || mc.player == null) return;
        mc.player.hurtTo(h);
    }

    private void onSpawnPos(NetIn in) {
        if (level == null || mc.player == null) return;
        int x = in.i32(), z = in.i32(), y = in.u8();
        Player p = mc.player;
        p.hasSpawn = true;
        p.spawnX = x;
        p.spawnY = y;
        p.spawnZ = z;
        level.spawnX = x;
        level.spawnY = y;
        level.spawnZ = z;
    }

    private void onAnimate(int action, int id) {
        if (level == null) return;
        Player p = mc.player;
        if (p != null && id == p.entityId) {
            if (action == Net.ANIM_WAKE_UP) mc.netWake();
            return;
        }
        Entity e = level.getEntity(id);
        if (!(e instanceof RemotePlayer)) return;
        RemotePlayer rp = (RemotePlayer) e;
        if (action == Net.ANIM_SWING) rp.swing();
        else if (action == Net.ANIM_WAKE_UP) rp.sleeping = false;
    }

    private void onRespawn(NetIn in) {
        if (level == null) return;
        int id = in.i32();
        float x = in.f32(), y = in.f32(), z = in.f32();
        Entity e = level.getEntity(id);
        if (e instanceof RemotePlayer) {
            RemotePlayer p = (RemotePlayer) e;
            p.moveTo(x, y, z, p.yRot, p.xRot);
            p.health = 20;
            p.deathTime = 0;
            p.hurtTime = 0;
        }
    }

    /** handle(ExplodePacket): Explosion::finalizeExplosion — звук, частицы, блоки без выпадения. */
    private void onExplode(NetIn in) {
        if (level == null) return;
        float x = in.f32(), y = in.f32(), z = in.f32(), r = in.f32();
        int n = in.i32();
        if (n < 0 || n > 100000) return;
        int xp = (int) x, yp = (int) y, zp = (int) z;
        int[] blow = new int[n * 3];
        for (int i = 0; i < n; i++) {
            blow[i * 3] = xp + in.s8();
            blow[i * 3 + 1] = yp + in.s8();
            blow[i * 3 + 2] = zp + in.s8();
        }
        level.finalizeExplosionNet(x, y, z, r, blow, n);
    }

    private void onLevelEvent(NetIn in) {
        if (level == null) return;
        int type = in.s16(), x = in.s16(), y = in.s16(), z = in.s16();
        in.i32();
        if (type == Net.LEVEL_SOUND_OPEN_DOOR) mc.playSoundAt(Mth.random() < 0.5f ? "random.door_open" : "random.door_close", x + 0.5f, y + 0.5f, z + 0.5f, 1f);
    }

    private void onSignUpdate(NetIn in) {
        if (level == null) return;
        int x = in.s16(), y = in.u8(), z = in.s16();
        TileEntity te = level.getTileEntity(x, y, z);
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = in.strLe();
            if (lines[i].length() > 15) lines[i] = lines[i].substring(0, 15);
        }
        if (te == null) {
            int t = level.getTile(x, y, z);
            if (t != Tile.SIGN && t != Tile.WALL_SIGN) return;
            te = new TileEntity("Sign", x, y, z);
            level.setTileEntity(te);
        }
        if (te.text == null || mc.screen instanceof TextEditScreen && ((TextEditScreen) mc.screen).sign == te) return;
        for (int i = 0; i < 4; i++) te.text[i] = lines[i];
        if (mc.signs != null) mc.signs.forget(level);
    }

    // ---- контейнеры: окно открывает хост (ServerPlayer::openContainer) ----

    private void onContainerOpen(NetIn in) {
        if (level == null || mc.player == null) return;
        int cid = in.u8(), type = in.u8();
        in.u8();
        in.str();
        if (type != Net.CT_CONTAINER && type != Net.CT_FURNACE) return;
        // LocalPlayer::openContainer/openFurnace: в творческом окна нет
        if (mc.player.inv.creative) return;
        TileEntity te = new TileEntity(type == Net.CT_FURNACE ? "Furnace" : "Chest", 0, -100, 0);
        container = te;
        containerId = cid;
        for (int i = 0; i < te.id.length; i++) cId[i] = cCount[i] = cAux[i] = 0;
        mc.setScreen(type == Net.CT_FURNACE ? (Screen) new FurnaceScreen(te) : new ChestScreen(te));
    }

    private void onContainerClose() {
        if (container == null) return;
        container = null;
        containerId = -1;
        if (mc.screen instanceof ChestScreen || mc.screen instanceof FurnaceScreen) mc.setScreen(null);
    }

    private void setSlot(int slot, int item, int count, int aux) {
        TileEntity te = container;
        if (slot < 0 || slot >= te.id.length) return;
        if (item <= 0 || count <= 0) item = count = aux = 0;
        te.id[slot] = cId[slot] = item;
        te.count[slot] = cCount[slot] = count;
        te.aux[slot] = cAux[slot] = aux;
    }

    private void onContainerSetSlot(NetIn in) {
        int cid = in.u8(), slot = in.s16(), item = in.u16(), count = in.u8(), aux = in.s16();
        if (container == null || cid != containerId) return;
        setSlot(slot, item, count, aux);
    }

    private void onContainerSetContent(NetIn in) {
        int cid = in.u8(), n = in.s16();
        if (container == null || cid != containerId) return;
        for (int i = 0; i < n && in.remaining() >= 5; i++) setSlot(i, in.u16(), in.u8(), in.s16());
    }

    private void onContainerSetData(NetIn in) {
        int cid = in.u8(), id = in.s16(), v = in.s16();
        if (container == null || cid != containerId) return;
        if (id == 0) container.cookTime = v;
        if (id == 1) container.burnTime = v;
        if (id == 2) container.litDuration = v;
    }

    /** Свои перекладывания в окне — ContainerSetSlotPacket хосту. */
    private void containerChanges() {
        TileEntity te = container;
        for (int i = 0; i < te.id.length; i++) {
            if (te.id[i] == cId[i] && te.count[i] == cCount[i] && te.aux[i] == cAux[i]) continue;
            cId[i] = te.id[i];
            cCount[i] = te.count[i];
            cAux[i] = te.aux[i];
            NetOut o = Net.out.reset();
            o.u8(Net.CONTAINERSETSLOT);
            o.u8(containerId);
            o.u16(i);
            Net.writeItem(o, te.id[i], te.count[i], te.aux[i]);
            send(o);
        }
    }

    /** окно закрыто у нас (LocalPlayer::closeContainer) */
    public void containerClosed(TileEntity te) {
        if (te != container || container == null) return;
        containerChanges();
        NetOut o = Net.out.reset();
        o.u8(Net.CONTAINERCLOSE);
        o.u8(containerId);
        send(o);
        container = null;
        containerId = -1;
    }

    // ---- свой сон: хост присылает флаг в SetEntityData ----

    void ownSleepFlag(boolean sleeping) {
        if (sleeping && !wasSleepFlag) mc.netSleep(bedX, bedY, bedZ);
        else if (!sleeping && wasSleepFlag) mc.netWake();
        wasSleepFlag = sleeping;
    }

    void ownBedPos(int x, int y, int z) {
        bedX = x;
        bedY = y;
        bedZ = z;
        if (wasSleepFlag && mc.player != null && !mc.player.sleeping) mc.netSleep(x, y, z);
    }

    // ---- свои действия (GameMode / LocalPlayer при isClientSide) ----

    public void removeBlock(int x, int y, int z) {
        NetOut o = Net.out.reset();
        o.u8(Net.REMOVEBLOCK);
        o.i32(mc.player.entityId);
        o.i32(x);
        o.i32(z);
        o.u8(y);
        send(o);
    }

    /** UseItemPacket по блоку (до своего Item::useOn, как GameMode::useItemOn). */
    public void useItemOn(int x, int y, int z, int face, float cx, float cy, float cz) {
        Player p = mc.player;
        int item = p.inv.selCount() > 0 ? p.inv.selId() : 0, aux = p.inv.selCount() > 0 ? p.inv.selAux() : 0;
        NetOut o = Net.out.reset();
        o.u8(Net.USEITEM);
        o.i32(x);
        o.i32(y);
        o.i32(z);
        o.i32(face);
        o.u16(item);
        o.u8(aux);
        o.i32(p.entityId);
        o.f32(cx);
        o.f32(cy);
        o.f32(cz);
        send(o);
    }

    /** UseItemPacket(грань 255): направление взгляда * 32768 (GameMode::useItem). */
    public void useItem() {
        Player p = mc.player;
        int item = p.inv.selCount() > 0 ? p.inv.selId() : 0, aux = p.inv.selCount() > 0 ? p.inv.selAux() : 0;
        float c = Mth.cos(p.xRot * Mth.DEG);
        float ax = -Mth.sin(p.yRot * Mth.DEG) * c, ay = -Mth.sin(p.xRot * Mth.DEG), az = Mth.cos(p.yRot * Mth.DEG) * c;
        NetOut o = Net.out.reset();
        o.u8(Net.USEITEM);
        o.i32((int) (ax * 32768f));
        o.i32((int) (ay * 32768f));
        o.i32((int) (az * 32768f));
        o.i32(255);
        o.u16(item);
        o.u8(aux);
        o.i32(p.entityId);
        o.f32(0);
        o.f32(0);
        o.f32(0);
        send(o);
    }

    public void interact(int action, Entity target) {
        NetOut o = Net.out.reset();
        o.u8(Net.INTERACT);
        o.u8(action);
        o.i32(mc.player.entityId);
        o.i32(target.entityId);
        send(o);
    }

    public void swing() {
        NetOut o = Net.out.reset();
        o.u8(Net.ANIMATE);
        o.u8(Net.ANIM_SWING);
        o.i32(mc.player.entityId);
        sendUnreliable(o);
    }

    public void playerAction(int action) {
        NetOut o = Net.out.reset();
        o.u8(Net.PLAYERACTION);
        o.i32(action);
        o.i32(0);
        o.i32(0);
        o.i32(0);
        o.i32(0);
        o.i32(mc.player.entityId);
        send(o);
    }

    public void useItemComplete() {
        NetOut o = Net.out.reset();
        o.u8(Net.ENTITYEVENT);
        o.i32(mc.player.entityId);
        o.u8(Net.EV_USE_ITEM_COMPLETE);
        send(o);
    }

    /** LocalPlayer::causeFallDamage: урон считает хост. */
    public void fallDamage(int dmg) {
        NetOut o = Net.out.reset();
        o.u8(Net.SETHEALTH);
        o.u8(Net.HEALTH_MODIFY_OFFSET + dmg);
        send(o);
    }

    /** LocalPlayer::die: инвентарь хосту на выброс, броня — по одной вещи. */
    public void died() {
        Player p = mc.player;
        Inventory inv = p.inv;
        NetOut o = Net.out.reset();
        o.u8(Net.SENDINVENTORY);
        o.i32(p.entityId);
        o.u8(1);
        o.u16(Inventory.SLOTS);
        for (int i = 0; i < Inventory.SLOTS; i++) {
            if (inv.creative || inv.id[i] == 0 || inv.count[i] <= 0) Net.writeItem(o, 0, 0, 0);
            else Net.writeItem(o, inv.id[i], inv.count[i], inv.aux[i]);
        }
        for (int i = 0; i < 4; i++) Net.writeItem(o, p.armor[i], p.armor[i] != 0 ? 1 : 0, p.armorDmg[i]);
        send(o);
        for (int i = 0; i < 4; i++) {
            if (!Item.isArmor(p.armor[i])) continue;
            o = Net.out.reset();
            o.u8(Net.DROPITEM);
            o.i32(p.entityId);
            o.u8(1);
            Net.writeItem(o, p.armor[i], 1, p.armorDmg[i]);
            send(o);
        }
    }

    public void respawned() {
        Player p = mc.player;
        NetOut o = Net.out.reset();
        o.u8(Net.RESPAWN);
        o.i32(p.entityId);
        o.f32(p.x);
        o.f32(p.y + Player.EYE);
        o.f32(p.z);
        send(o);
    }

    public void signChanged(TileEntity te) {
        NetServer.writeSign(Net.out.reset(), te);
        send(Net.out);
    }
}
