package com.searchbox.utils;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import org.apache.commons.codec.binary.Base64;


public class DecryptLicense{
    private static String privkey= "MIIBVgIBADANBgkqhkiG9w0BAQEFAASCAUAwggE8AgEAAkEAoprA69iKxp4+xqx/g7ffCgUuxS9j3XiLdfOh5n/gfDxYVXOgHRQbuXgig3mxraCcvRFImf/RBbSK+Rcz7pA4PwIDAQABAkAF57DR389KXzzQYjtPQUIsTvvf1VS/Gj2WTv62LDauzENThgPSjbxyE4uZvQeGxXVX0odrsW/nDdTBMjTXP/KBAiEA4fquVB6zNkTYkyR6hgmUGogmM0RtWW/cS/u5SalGHBECIQC4NMOlbMkf/XHF4AEviNZI2G2H+PnFaHtVCYaR6Y+fTwIhAM8SvShMMYBrOeIrrTKiGFrvo8Ga1HD+NepSsokzWhFhAiEAs4mZ9y4kia14qqg9/5qbYLyxZQniR+oh6ywxoR3IAjkCIQDCTBZ29D9ZyAzP7V2A8KhnuHzdK4TY+za3xjv+uLeQ4A==";
    private static String xform = "RSA/ECB/PKCS1PADDING";
    private static String algorithm = "RSA";
  
    private static boolean checkDate(String dateString) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
            return format.parse(dateString).after(new Date());
        } catch (ParseException ex) {
            Logger.getLogger(DecryptLicense.class.getName()).log(Level.SEVERE, null, ex);
        }
        return false;
    }
    
    public static boolean checkLicense(String lkey, String productName){
        try {
            String licval=new String(decrypt(Base64.decodeBase64(lkey)));
            String [] lines=licval.split("\n");
            boolean datecheck= checkDate(lines[1]);
            return datecheck & lines[2].contains(productName);
        } catch (Exception ex) {
            Logger.getLogger(DecryptLicense.class.getName()).log(Level.SEVERE, null, ex);
        }
        return false;
    }

       public static byte[] decrypt(byte[] inpBytes) throws Exception {
        
        byte[] pkbytes = Base64.decodeBase64(privkey);
        KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
        PKCS8EncodedKeySpec privateKeySpec = new  PKCS8EncodedKeySpec(pkbytes);
        PrivateKey pk = keyFactory.generatePrivate(privateKeySpec);
        
        Cipher cipher = Cipher.getInstance(xform);
        cipher.init(Cipher.DECRYPT_MODE, pk);
        return cipher.doFinal(inpBytes);
    }
    


}
