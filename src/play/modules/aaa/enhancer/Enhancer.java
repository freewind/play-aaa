package play.modules.aaa.enhancer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javassist.*;
import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.modules.aaa.*;
import play.modules.aaa.utils.AAAFactory;
import play.modules.aaa.utils.AnnotationHelper;
import play.modules.aaa.utils.ConfigConstants;

public class Enhancer extends play.classloading.enhancers.Enhancer {

    @Override
    public void enhanceThisClass(ApplicationClass applicationClass)
            throws Exception {
        enhance_(applicationClass, false);
    }

    private void enhance_(ApplicationClass applicationClass,
            boolean buildAuthorityRegistryOnly) throws Exception {
        Plugin.trace("about to enhance applicationClass: %s", applicationClass);
        CtClass ctClass = makeClass(applicationClass);
        Set<CtBehavior> s = new HashSet<CtBehavior>();
        s.addAll(Arrays.asList(ctClass.getDeclaredMethods()));
        s.addAll(Arrays.asList(ctClass.getMethods()));
        s.addAll(Arrays.asList(ctClass.getConstructors()));
        s.addAll(Arrays.asList(ctClass.getDeclaredConstructors()));
        for (final CtBehavior ctBehavior : s) {
            if (!Modifier.isPublic(ctBehavior.getModifiers()) || javassist.Modifier.isAbstract(ctBehavior.getModifiers())) {
                continue;
            }

            boolean needsEnhance = false;
            RequireRight rr = null;
            RequirePrivilege rp = null;
            RequireAccounting ra = null;
            boolean allowSystem = false;
            Object[] aa = ctBehavior.getAnnotations();
            for (Object o : aa) {
                if (o instanceof RequirePrivilege) {
                    needsEnhance = true;
                    rp = (RequirePrivilege) o;
                    continue;
                }
                if (o instanceof RequireRight) {
                    needsEnhance = true;
                    rr = (RequireRight) o;
                    continue;
                }
                if (o instanceof AllowSystemAccount) {
                    allowSystem = true;
                    continue;
                }
                if (o instanceof RequireAccounting) {
                    needsEnhance = true;
                    ra = (RequireAccounting) o;
                }
            }
            if (!needsEnhance)
                continue;

            String key = ctBehavior.getLongName();
            String errMsg = String.format("Error enhancing class %s.%s: ", ctClass, ctBehavior);
            // process rr & rp
            if (null != rr || null != rp) {
                // check before/after enhancement
                Authority.registAuthoriable_(key, rr, rp);
                if (!buildAuthorityRegistryOnly) {
                    // verify if before attribute of rr and rp is consistent
                    if (null != rr && null != rp && (rr.before() != rp.before())) {
                        String reason = "The before setting of RequireRight and RequirePrivilege doesn't match";
                        throw new RuntimeException(errMsg + reason);
                    }
                    boolean before = true;
                    if (null != rr) before = rr.before();
                    if (null != rp) before = rp.before();
                    // try best to guess the target object
                    String curObj = "";
                    if (null != rr) {
                        // target object only impact dynamic access checking, hence rr shall not be null
                        boolean isConstructor = ctBehavior instanceof CtConstructor;
                        boolean isStatic = false;
                        if (!isConstructor) isStatic = Modifier.isStatic(ctBehavior.getModifiers());
                        int paraCnt = ctBehavior.getParameterTypes().length;
                        int id = rr.target();
                        // calibrate target id
                        if (0 == id) {
                            if (isConstructor) {
                                id = -1;
                            } else if (isStatic) {
                                if (paraCnt > 0) id = 1;
                                else id = -1;
                            }
                        } else if (id > paraCnt) {
                            id = paraCnt;
                        }
                        // speculate cur target statement
                        String sid = null;
                        if (id == -1) sid = "_";
                        if (id > -1) sid = String.valueOf(id);
                        if (null != sid) {
                            curObj = "play.modules.aaa.PlayDynamicRightChecker.setObjectIfNoCurrent($" + sid + ");";
                        }

                        if (-1 == id) before = false;
                    }
                    // check permission enhancement
                    if (before) {
                        ctBehavior.insertBefore(curObj + " play.modules.aaa.enhancer.Enhancer.Authority.checkPermission(\""
                                + key + "\", " + Boolean.toString(allowSystem) + ");");
                    } else {
                        ctBehavior.insertAfter(curObj + " play.modules.aaa.enhancer.Enhancer.Authority.checkPermission(\""
                                + key + "\", " + Boolean.toString(allowSystem) + ");");
                    }
                }
            }

            if (buildAuthorityRegistryOnly) continue;

            // process ra
            if (null != ra) {
                CtClass[] paraTypes = ctBehavior.getParameterTypes();
                String  sParam = null;
                if (0 < paraTypes.length) {
                    sParam = "new Object[0]";
                } else {
                    sParam = "{$$}";
                }
                String msg = ra.value();
                if (null == msg || "".equals(msg))
                    msg = key;
                if (ra.before()) {
                    ctBehavior.insertBefore("play.modules.aaa.utils.Accounting.info(\""
                            + msg
                            + "\", "
                            + Boolean.toString(allowSystem)
                            + ", " + sParam + ");");
                } else {
                    ctBehavior.insertAfter("play.modules.aaa.utils.Accounting.info(\""
                            + msg
                            + "\", "
                            + Boolean.toString(allowSystem)
                            + ", " + sParam + ");");
                }
                CtClass etype = ClassPool.getDefault().get(
                        "java.lang.Exception");
                ctBehavior.addCatch(
                        "{play.modules.aaa.utils.Accounting.error($e, \""
                                + msg + "\", "
                                + Boolean.toString(allowSystem)
                                + ", " + sParam + "); throw $e;}", etype);
            }
        }

        if (buildAuthorityRegistryOnly) return;

        applicationClass.enhancedByteCode = ctClass.toBytecode();
        ctClass.detach();
    }

    public void buildAuthorityRegistry() throws Exception {
        if (Authority.reg_.size() < 1) {
            Plugin.debug("building authority registry");
            // force build authority registry
            for (ApplicationClass ac : Play.classes.all()) {
                enhance_(ac, true);
            }
        }
        Authority.ensureRightPrivilege();
    }

    public void rebuildAuthorityRegistry() throws Exception {
        Authority.reg_.clear();
        buildAuthorityRegistry();
    }

    public static class Authority implements IAuthorizeable {

        private IRight r_ = null;
        private IPrivilege p_ = null;

        private RequireRight rr_ = null;
        private RequirePrivilege rp_ = null;
        private String key_;

        private Authority(String key, RequireRight rr, RequirePrivilege rp) {
            rr_ = rr;
            rp_ = rp;
            key_ = key;
        }

        public void validate() {
            IRight r = getRequiredRight();
            IPrivilege p = getRequiredPrivilege();
            if (r == null && p == null) {
                throw new RuntimeException(
                        "Invalid AAA annotation found: neither privilege nor right could be identified for "
                                + key_);
            }
        }

        @Override
        public IRight getRequiredRight() {
            if (null == rr_)
                return null;
            if (null == r_) {
                r_ = AnnotationHelper.getRequiredRight(rr_, key_);
            }
            return r_;
        }

        @Override
        public IPrivilege getRequiredPrivilege() {
            if (null == rp_)
                return null;
            if (null == p_) {
                p_ = AnnotationHelper.getRequiredPrivilege(rp_, key_);
            }
            return p_;
        }

        private static final Map<String, Authority> reg_ = new HashMap<String, Authority>();

        private static void registAuthoriable_(String key, RequireRight rr,
                RequirePrivilege rp) {
            if (Logger.isTraceEnabled()) {
                Plugin.trace("register authoriable [%s: (%s|%s)]", key, (null == rr) ? "null-right" : rr.value(), (null == rp) ? "null-privilege" : rp.value() );
            }
            reg_.put(key, new Authority(key, rr, rp));
        }

        public static IAuthorizeable getRight(String key) {
            return reg_.get(key);
        }

        public static void ensureRightPrivilege() {
            IRight rightFact = AAAFactory.right();
            IPrivilege privFact = AAAFactory.privilege();
            for (Authority a : reg_.values()) {
                RequireRight rr = a.rr_;
                IRight r = a.r_;
                if (rr != null && r == null) {
                    String s = rr.value();
                    if (null == s)
                        throw new NullPointerException(
                                "Null RequireRight found for " + a.key_);
                    if (s.startsWith("aaa"))
                        s = Play.configuration.getProperty(s);
                    r = rightFact.getByName(s);
                    if (null == r) {
                        r = rightFact.create(s);
                        r._save();
                    }
                    a.r_ = r;
                }

                RequirePrivilege rp = a.rp_;
                IPrivilege p = a.p_;
                if (rp != null && p == null) {
                    String s = rp.value();
                    if (null == s)
                        throw new NullPointerException(
                                "Null RequirePrivilege found for " + a.key_);
                    if (s.startsWith("aaa"))
                        s = Play.configuration.getProperty(s);
                    p = privFact.getByName(s);
                    if (null == p) {
                        p = privFact.create(s, 0);
                        p._save();
                    }
                    a.p_ = p;
                }
            }
        }

        public static void checkPermission(String key, boolean allowSystem)
                throws NoAccessException {
            long l = 0;
            if (Plugin.logCheckTime && Logger.isDebugEnabled()) {
                Plugin.debug(">>>>>>> [%s]", key);
                l = System.currentTimeMillis();
            }
            if (Boolean.parseBoolean(Play.configuration.getProperty(
                    ConfigConstants.DISABLE, "false"))) {
                return;
            }

            IAuthorizeable a = reg_.get(key);
            if (null == a) {
                throw new RuntimeException(
                        "oops, something wrong with enhancer... ?");
            }
            IAccount acc = null;
            try {
                IAccount accFact = AAAFactory.account();
                acc = accFact.getCurrent();
                if (null == acc) {
                    if (allowSystem) {
                        if (!Boolean
                                .parseBoolean(Play.configuration
                                        .getProperty(
                                                ConfigConstants.SYSTEM_PERMISSION_CHECK,
                                                "false"))) {
                            // suppress permission check for system account
                            return;
                        }
                        acc = accFact.getSystemAccount();
                    }
                    if (null == acc) {
                        throw new NoAccessException(
                                "cannot determine principal account");
                    }
                }

                // superuser check
                boolean isSuperUser = false;
                if (Plugin.superuser > 0) {
                    IPrivilege p = acc.getPrivilege();
                    if (null != p) isSuperUser = p.getLevel() >= Plugin.superuser;
                }
                if (!isSuperUser && !acc.hasAccessTo(a)) {
                    throw new NoAccessException("Access denied");
                }
            } catch (NoAccessException nae) {
                throw nae;
            } catch (Exception e) {
                throw new NoAccessException(e);
            } finally {
                if (Plugin.logCheckTime && Logger.isDebugEnabled()) {
                    Plugin.debug("<<<<<<< [%s]: %sms", key, System.currentTimeMillis() - l);
                }
            }
        }
    }
}
