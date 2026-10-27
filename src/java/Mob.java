import java.util.Random;

/** Живое существо — Mob 0.6.1: baseTick (звуки, удушье, утопление, смерть), aiStep, travel, урон. */
public abstract class Mob extends Entity {
    public int health, hurtTime, deathTime, attackTime, lastHealth = 20;
    /** Mob::xxa / yya — вбок и вперёд */
    public float moveForward, moveStrafe;
    public boolean jumping;
    /** walkAnimPos, walkAnimSpeed */
    public float walkAnim, walkAnimO, walkSpeed, walkSpeedO;
    public float bodyRot, bodyRotO, headRot;
    protected final Random rnd = new Random(Mth.nextSeed());
    public float hurtDir;
    private int ambientChance;
    protected Entity lastHurtBy;
    /** Mob::lastHurt / dmgSpill */
    protected int lastHurt, dmgSpill;
    /** Mob::walkingSpeed / flyingSpeed / runSpeed / yRotA / noActionTime */
    protected float walkingSpeed = 0.1f, flyingSpeed = 0.02f, runSpeed = 0.7f, yRotA;
    protected int noActionTime;
    private Entity lookingAt;
    private int lookTime;
    /** Mob::swing/updateAttackAnim */
    public boolean swinging;
    public int swingTime;
    public float attackAnim, attackAnimO;

    protected Mob(Level l) {
        super(l);
        health = maxHealth();
        ambientChance = -l.random.nextInt(100);
        yRot = (float) (Mth.random() * Mth.PI * 2);
    }

    public int maxHealth() {
        return 10;
    }

    public boolean isAlive() {
        return !removed && health > 0;
    }

    public boolean blocksBuilding() {
        return true;
    }

    public boolean isPushable() {
        return !removed;
    }

    protected boolean makeStepSound() {
        return true;
    }

    /** Mob::tick: Entity::tick (baseTick), aiStep, анимация ходьбы, поворот тела. */
    public void tick() {
        baseTick();
        aiStep();
        walkSpeedO = walkSpeed;
        walkAnimO = walkAnim;
        float dx = x - xo, dz = z - zo;
        float side = Mth.sqrt(dx * dx + dz * dz);
        // Mob::updateWalkAnim
        float wst = side * 4;
        if (wst > 1) wst = 1;
        walkSpeed += (wst - walkSpeed) * 0.4f;
        walkAnim += walkSpeed;
        float target = bodyRot;
        if (side > 0.05f) target = Mth.atan2(dz, dx) * 180f / Mth.PI - 90;
        if (attackAnim > 0) target = yRot;
        bodyRot += Mth.wrapDegrees(target - bodyRot) * 0.3f;
        float head = Mth.wrapDegrees(yRot - bodyRot);
        if (head < -75) head = -75;
        if (head >= 75) head = 75;
        bodyRot = yRot - head;
        if (head * head > 50 * 50) bodyRot += head * 0.2f;
    }

    /** Mob::baseTick */
    public void baseTick() {
        attackAnimO = attackAnim;
        super.baseTick();
        // проверка раз в 16 тиков, после звука пауза getAmbientSoundInterval (животные 12 с, прочие 8 с)
        if (((ambientChance++ & 15) == 0) && rnd.nextInt(2000) < ambientChance) {
            ambientChance = -ambientInterval();
            sound(ambientSound());
        }
        // удушье: голова в сплошном блоке
        if (isAlive() && isInWall()) hurt(1, null);
        // утопление: 8 пузырей и урон 2 раз в секунду, когда воздух кончился
        if (isAlive() && isUnderWater() && !isWaterMob()) {
            air--;
            if (air == -20) {
                air = 0;
                if (MC.instance != null)
                    for (int i = 0; i < 8; i++) {
                        float xo = rnd.nextFloat() - rnd.nextFloat(), yo = rnd.nextFloat() - rnd.nextFloat(), zo = rnd.nextFloat() - rnd.nextFloat();
                        MC.instance.particles.bubble(x + xo, Level.origY(this) + yo, z + zo, xd, yd, zd);
                    }
                hurt(2, null);
            }
        } else air = 300;
        if (attackTime > 0) attackTime--;
        if (hurtTime > 0) hurtTime--;
        if (invulnerableTime > 0) invulnerableTime--;
        if (health <= 0) {
            deathTime++;
            if (deathTime > 20 && !isPlayerLike()) {
                beforeRemove();
                remove();
                // 20 частиц explode на месте умершего моба
                if (MC.instance != null)
                    for (int i = 0; i < 20; i++) {
                        float xa = (2 * rnd.nextFloat() - 1) * (2 * rnd.nextFloat() - 1) * 0.02f;
                        float ya = (2 * rnd.nextFloat() - 1) * (2 * rnd.nextFloat() - 1) * 0.02f;
                        float za = (2 * rnd.nextFloat() - 1) * (2 * rnd.nextFloat() - 1) * 0.02f;
                        MC.instance.particles.explode(x + rnd.nextFloat() * bbW * 2 - bbW, y + rnd.nextFloat() * bbH, z + rnd.nextFloat() * bbW * 2 - bbW, xa, ya, za);
                    }
            }
        }
        bodyRotO = bodyRot;
    }

    /** игрок (свой или чужой из сети): после смерти не исчезает — ждёт возрождения */
    public boolean isPlayerLike() {
        return this instanceof Player;
    }

    /** Mob::lerpTo: клиент сетевой игры — положение от хоста за 3 тика */
    float lx, ly, lz, lyr, lxr;
    int lSteps;

    public void lerpTo(float x, float y, float z, float yRot, float xRot) {
        lx = x; ly = y; lz = z; lyr = yRot; lxr = xRot;
        lSteps = 3;
    }

    /** Mob::handleEntityEvent: урон и смерть от хоста */
    public void netEvent(int ev) {
        if (ev == Net.EV_HURT) {
            walkSpeed = 1.5f;
            invulnerableTime = 20;
            hurtTime = 10;
            hurtDir = 0;
            sound(hurtSound());
        } else if (ev == Net.EV_DEATH) {
            sound(deathSound());
            health = 0;
        }
    }

    /** Entity::isInWall: клетка на высоте головы — сплошной кубический блок. */
    public boolean isInWall() {
        return level.isSolidBlockingTile(Mth.floor(x), Mth.floor(Level.origY(this) + headHeightOrig()), Mth.floor(z));
    }

    /** Mob::getHeadHeight (у игрока 0.12 над глазами) */
    protected float headHeightOrig() {
        return bbH * 0.85f;
    }

    protected boolean isWaterMob() {
        return false;
    }

    protected int ambientInterval() {
        return 8 * 20;
    }

    protected void beforeRemove() {
    }

    protected void outOfWorld() {
        int old = health;
        hurt(4, null);
        if (health >= old) actuallyHurt(4);
    }

    /** Mob::aiStep */
    protected void aiStep() {
        updateAttackAnim();
        if (level.isClientSide) {
            // Mob::interpolateOnly: у клиента ИИ нет — только сглаживание к положению от хоста
            jumping = false;
            moveStrafe = moveForward = yRotA = 0;
            if (lSteps > 0) {
                x += (lx - x) / lSteps;
                y += (ly - y) / lSteps;
                z += (lz - z) / lSteps;
                yRot += Mth.wrapDegrees(lyr - yRot) / lSteps;
                xRot += (lxr - xRot) / lSteps;
                lSteps--;
            } else if (xd != 0 || yd != 0 || zd != 0) travel(0, 0);
            return;
        }
        if (health <= 0) {
            jumping = false;
            moveStrafe = moveForward = yRotA = 0;
        } else {
            long t0 = Prof.t();
            updateAi();
            if (Prof.on) Prof.add(Prof.T_AI, t0);
        }
        if (jumping) {
            if (inWater || inLava) yd += 0.04f;
            else if (onGround) jumpFromGround();
        }
        moveStrafe *= 0.98f;
        moveForward *= 0.98f;
        yRotA *= 0.9f;
        float ws = walkingSpeed, fs = flyingSpeed, mod = walkingSpeedModifier();
        walkingSpeed *= mod;
        flyingSpeed *= mod;
        travel(moveStrafe, moveForward);
        walkingSpeed = ws;
        flyingSpeed = fs;
        pushEntities();
    }

    /** Mob::aiStep: толкать соседей в рамке +0.2 по горизонтали. */
    protected void pushEntities() {
        java.util.Vector v = level.entities;
        float hw = bbW / 2 + 0.2f;
        for (int i = -1; i < v.size(); i++) {
            Entity e = i < 0 ? level.player : (Entity) v.elementAt(i);
            if (e == null || e == this || !e.isPushable()) continue;
            if (e instanceof Player && ((Player) e).sleeping) continue;
            float ew = e.bbW / 2;
            if (e.x + ew > x - hw && e.x - ew < x + hw && e.z + ew > z - hw && e.z - ew < z + hw && e.y + e.bbH > y && e.y < y + bbH)
                e.push(this);
        }
    }

    protected void jumpFromGround() {
        yd = 0.42f;
    }

    /** Mob::getWalkingSpeedModifier 0.6.1 — 0.7 */
    protected float walkingSpeedModifier() {
        return 0.7f;
    }

    /** Mob::updateAi: простой ИИ — оглядываться на игрока, поворачиваться. */
    protected void updateAi() {
        noActionTime++;
        checkDespawn();
        moveStrafe = moveForward = 0;
        float lookDistance = 8;
        Player p = level.player;
        if (rnd.nextFloat() < 0.02f) {
            if (p != null && !p.removed && p.distanceToSqr(this) < lookDistance * lookDistance) {
                lookingAt = p;
                lookTime = 10 + rnd.nextInt(20);
            } else yRotA = (rnd.nextFloat() - 0.5f) * 20;
        }
        if (lookingAt != null) {
            Entity la = lookingAt;
            lookingAt = null;
            lookAt(la, 10, maxHeadXRot());
            if (!(lookTime-- <= 0 || la.removed || la.distanceToSqr(this) > lookDistance * lookDistance)) lookingAt = la;
        } else {
            if (rnd.nextFloat() < 0.05f) yRotA = (rnd.nextFloat() - 0.5f) * 20;
            yRot += yRotA;
            xRot = 0;
        }
        if (inWater || inLava) jumping = rnd.nextFloat() < 0.8f;
    }

    protected int maxHeadXRot() {
        return 10;
    }

    protected boolean removeWhenFarAway() {
        return true;
    }

    /** Mob::checkDespawn: дальше 96 блоков — исчезнуть (счётчик бездействия в 0.6.1 тут же обнуляется). */
    protected void checkDespawn() {
        if (level.player == null) return;
        float sd = level.nearestPlayerDist2(x, y, z);
        boolean far = removeWhenFarAway();
        if (far && sd > 96 * 96) remove();
        if (noActionTime > 30 * 20 && rnd.nextInt(800) == 0 && far && sd > 32 * 32) remove();
        else noActionTime = 0;
    }

    /** Mob::lookAt */
    protected void lookAt(Entity e, float yMax, float xMax) {
        float xd = e.x - x, zd = e.z - z;
        float yd = (e.y + e.y + e.bbH) / 2 - (y + headHeightOrig());
        float sd = Mth.sqrt(xd * xd + zd * zd);
        float yRotD = Mth.atan2(zd, xd) * 180f / Mth.PI - 90;
        float xRotD = Mth.atan2(yd, sd) * 180f / Mth.PI;
        xRot = -rotlerp(xRot, xRotD, xMax);
        yRot = rotlerp(yRot, yRotD, yMax);
    }

    static float rotlerp(float a, float b, float max) {
        float d = Mth.wrapDegrees(b - a);
        if (d > max) d = max;
        if (d < -max) d = -max;
        return a + d;
    }

    /** Mob::canSee: луч от глаз до глаз цели не задевает блоков. */
    protected boolean canSee(Entity t) {
        return !level.clip(x, y + headHeightOrig(), z, t.x, Level.origY(t) + (t instanceof Mob ? ((Mob) t).headHeightOrig() : 0), t.z, false, false);
    }

    protected String ambientSound() { return null; }
    protected String hurtSound() { return "random.hurt"; }
    protected String deathSound() { return "random.hurt"; }

    protected float soundVolume() {
        return 1f;
    }

    /** Звук с громкостью по расстоянию до игрока. */
    protected void sound(String name) {
        if (name == null || MC.instance == null || level.player == null) return;
        MC.instance.playSoundAt(name, x, y, z, soundVolume());
    }

    /** Mob::onLadder: лестница в ногах или на блок выше (паук — когда упёрся в стену). */
    protected boolean onLadder() {
        int xt = Mth.floor(x), yt = Mth.floor(y), zt = Mth.floor(z);
        return level.getTile(xt, yt, zt) == Tile.LADDER || level.getTile(xt, yt + 1, zt) == Tile.LADDER;
    }

    /** Mob::travel */
    protected void travel(float xa, float ya) {
        if (inWater) {
            float yo = y;
            moveRelative(xa, ya, 0.02f);
            move(xd, yd, zd);
            xd *= 0.8f; yd *= 0.8f; zd *= 0.8f;
            yd -= 0.02f;
            if (horizontalCollision && isFree(xd, yd + 0.6f - y + yo, zd)) yd = 0.3f;
        } else if (inLava) {
            float yo = y;
            moveRelative(xa, ya, 0.02f);
            move(xd, yd, zd);
            xd *= 0.5f; yd *= 0.5f; zd *= 0.5f;
            yd -= 0.02f;
            if (horizontalCollision && isFree(xd, yd + 0.6f - y + yo, zd)) yd = 0.3f;
        } else {
            float friction = groundFriction();
            float f2 = 0.16277136f / (friction * friction * friction);
            moveRelative(xa, ya, onGround ? walkingSpeed * f2 : flyingSpeed);
            friction = groundFriction();
            boolean ladder = onLadder();
            if (ladder) {
                fallDistance = 0;
                if (yd < -0.15f) yd = -0.15f;
                if (isSneakingNow() && yd < 0) yd = 0;
            }
            move(xd, yd, zd);
            if (horizontalCollision && onLadder()) yd = 0.2f;
            yd -= 0.08f;
            yd *= 0.98f;
            xd *= friction;
            zd *= friction;
        }
    }

    /** трение блока под ногами (Tile::friction * 0.91), в воздухе 0.91 */
    protected float groundFriction() {
        if (!onGround) return 0.91f;
        int t = level.getTile(Mth.floor(x), Mth.floor(y - 0.5f), Mth.floor(z));
        return (t > 0 ? Tile.friction[t] : 0.6f) * 0.91f;
    }

    /** Mob::causeFallDamage: damage.fallbig/fallsmall 0.75 и звук блока под ногами * 0.5. */
    protected void causeFall(float dist) {
        int dmg = (int) Math.ceil(dist - 3f);
        if (dmg <= 0) return;
        if (MC.instance != null) MC.instance.playSoundAt(dmg > 4 ? "damage.fallbig" : "damage.fallsmall", x, y, z, 0.75f);
        hurt(dmg, null);
        int t = level.getTile(Mth.floor(x), Mth.floor(y - 0.2f), Mth.floor(z));
        if (t > 0 && MC.instance != null) MC.instance.playSoundAt(Tile.stepSound(t), x, y, z, Tile.soundVolume(t) * 0.5f);
    }

    public int armorValue() {
        return 0;
    }

    protected void hurtArmor(int dmg) {
    }

    /** Mob::actuallyHurt с getDamageAfterArmorAbsorb */
    protected void actuallyHurt(int dmg) {
        int v = dmg * (25 - armorValue()) + dmgSpill;
        hurtArmor(dmg);
        dmgSpill = v % 25;
        health -= v / 25;
    }

    /** Mob::hurt 0.6.1 */
    public boolean hurt(int dmg, Entity source) {
        if (level.isClientSide) return false;
        noActionTime = 0;
        if (health <= 0) return false;
        walkSpeed = 1.5f;
        boolean snd = true;
        if (invulnerableTime > 10) {
            if (dmg <= lastHurt) return false;
            actuallyHurt(dmg - lastHurt);
            lastHurt = dmg;
            snd = false;
        } else {
            lastHurt = dmg;
            lastHealth = health;
            invulnerableTime = 20;
            actuallyHurt(dmg);
            hurtTime = 10;
        }
        hurtDir = 0;
        lastHurtBy = source;
        if (snd && level.net != null) level.net.entityEvent(this, Net.EV_HURT);
        if (snd) {
            if (source != null) {
                float xd = source.x - x, zd = source.z - z;
                while (xd * xd + zd * zd < 0.0001f) {
                    xd = (Mth.random() - Mth.random()) * 0.01f;
                    zd = (Mth.random() - Mth.random()) * 0.01f;
                }
                hurtDir = Mth.atan2(zd, xd) * 180f / Mth.PI - yRot;
                knockback(xd, zd);
            } else hurtDir = (int) (Mth.random() * 2f) * 180f;
        }
        if (health <= 0) {
            if (snd) sound(deathSound());
            if (level.net != null) level.net.entityEvent(this, Net.EV_DEATH);
            die(source);
        } else if (snd) sound(hurtSound());
        return true;
    }

    /** Mob::knockback */
    protected void knockback(float xa, float za) {
        float dd = 1f / Mth.sqrt(xa * xa + za * za);
        xd *= 0.5f; yd *= 0.5f; zd *= 0.5f;
        xd -= xa * dd * 0.4f;
        yd += 0.4f;
        zd -= za * dd * 0.4f;
        if (yd > 0.4f) yd = 0.4f;
    }

    /** Mob::die: лут выпадает при любой смерти. */
    protected void die(Entity source) {
        if (!level.isClientSide) dropLoot();
    }

    /** Mob::dropDeathLoot: nextInt(3) штук getDeathLoot */
    protected void dropLoot() {
    }

    protected void onDeathRemove() {
    }

    /** Entity::spawnAtLocation: по одному предмету у ног. */
    protected void drop(int id, int count, int aux) {
        for (int i = 0; i < count; i++) level.addEntity(new ItemEntity(level, x, y, z, id, 1, aux));
    }

    public boolean isPickable() {
        return !removed;
    }

    public void swing() {
        if (!swinging || swingTime >= 3 || swingTime < 0) {
            swingTime = -1;
            swinging = true;
            if (this == level.player) Net.localSwing();
        }
    }

    protected void updateAttackAnim() {
        if (swinging) {
            if (++swingTime >= 8) {
                swingTime = 0;
                swinging = false;
            }
        } else swingTime = 0;
        attackAnim = swingTime / 8f;
    }

    /** Mob::getAttackAnim */
    public float getAttackAnim(float a) {
        float d = attackAnim - attackAnimO;
        if (d < 0) d += 1;
        return attackAnimO + d * a;
    }

    /** Освещённость 0..15 на высоте 0.66 рамки (Entity::getBrightness). */
    public int brightnessHere() {
        return level.getRawBrightness(Mth.floor(x), Mth.floor(y + bbH * 0.66f), Mth.floor(z));
    }

    /** Entity::getBrightness: доля 0..1 по Dimension::brightnessRamp */
    public float brightness() {
        return LevelRenderer.BRF[brightnessHere()];
    }

    public void saveMob(Nbt.Compound t) {
        saveBase(t);
        t.putShort("Health", health);
        t.putShort("HurtTime", hurtTime);
        t.putShort("DeathTime", deathTime);
        t.putShort("AttackTime", attackTime);
    }

    public void loadMob(Nbt.Compound t) {
        loadBase(t);
        health = t.getShort("Health");
        hurtTime = t.getShort("HurtTime");
        deathTime = t.getShort("DeathTime");
        attackTime = t.getShort("AttackTime");
        bodyRot = bodyRotO = yRot;
    }

    public void save(Nbt.Compound t) {
        saveMob(t);
    }

    public void load(Nbt.Compound t) {
        loadMob(t);
    }
}
