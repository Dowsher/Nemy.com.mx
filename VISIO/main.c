/* Visio LED: local BLE actuator; no Wi-Fi or physiological samples. */
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include <inttypes.h>
#include <assert.h>
#include "nvs_flash.h"
#include "esp_log.h"
#include "esp_random.h"
#include "esp_timer.h"
#include "driver/gpio.h"
#include "led_strip.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "VisioLED";
static uint8_t own_type;
static uint16_t tx_handle;
static uint8_t last_ack[18] = {2};
static uint8_t seen[16][16];
static unsigned seen_count, seen_cursor;
static esp_timer_handle_t off_timer;
#if CONFIG_PIPALAB_LED_RGB
static led_strip_handle_t strip;
#endif
static bool led_ready;
void ble_store_config_init(void);

/* UUID bytes in NimBLE little-endian order. */
#define UUID_BYTES(x) 0x11,0xdc,0x8c,0x77,0x9e,0x4a,0x5f,0x99,0x31,0x4a,0x6a,0x2d,(x),0x4e,0xd8,0xa7
static const ble_uuid128_t service_uuid=BLE_UUID128_INIT(UUID_BYTES(0x20));
static const ble_uuid128_t rx_uuid=BLE_UUID128_INIT(UUID_BYTES(0x21));
static const ble_uuid128_t tx_uuid=BLE_UUID128_INIT(UUID_BYTES(0x22));
static int gap(struct ble_gap_event *event, void *arg);
static esp_err_t output(bool on) {
    if (!led_ready) return ESP_ERR_INVALID_STATE;
#if CONFIG_PIPALAB_LED_RGB
    if (on) {
        esp_err_t err=led_strip_set_pixel(strip,0,12,48,3);
        return err==ESP_OK ? led_strip_refresh(strip) : err;
    }
    return led_strip_clear(strip);
#else
    return gpio_set_level(CONFIG_PIPALAB_LED_GPIO,
#ifdef CONFIG_PIPALAB_LED_ACTIVE_LOW
        !on
#else
        on
#endif
    );
#endif
}
static void turn_off(void *arg) { (void)arg; output(false); }

static int access_led(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctx, void *arg) {
    (void)attr; (void)arg;
    struct ble_gap_conn_desc peer;
    if (ble_gap_conn_find(conn,&peer)!=0 || !peer.sec_state.encrypted || !peer.sec_state.authenticated)
        return BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
    if (ctx->op==BLE_GATT_ACCESS_OP_READ_CHR) {
        return os_mbuf_append(ctx->om,last_ack,sizeof(last_ack))==0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (ctx->op!=BLE_GATT_ACCESS_OP_WRITE_CHR || OS_MBUF_PKTLEN(ctx->om)!=19)
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    uint8_t command[19];
    if(os_mbuf_copydata(ctx->om,0,sizeof(command),command)!=0 || command[0]!=1)
        return BLE_ATT_ERR_UNLIKELY;
    unsigned duration=((unsigned)command[17]<<8)|command[18];
    if(duration<100 || duration>10000) return BLE_ATT_ERR_UNLIKELY;
    uint8_t result=0;
    bool duplicate=false;
    for(unsigned i=0;i<seen_count;i++) if(memcmp(seen[i],command+1,16)==0) duplicate=true;
    if(duplicate) result=1;
    else if(!led_ready) result=2;
    else {
        esp_timer_stop(off_timer);
        if(output(true)!=ESP_OK || esp_timer_start_once(off_timer,(uint64_t)duration*1000)!=ESP_OK) {
            output(false);result=3;
        } else {
            memcpy(seen[seen_cursor],command+1,16);
            seen_cursor=(seen_cursor+1)%16;if(seen_count<16)seen_count++;
        }
    }
    last_ack[0]=2;memcpy(last_ack+1,command+1,16);last_ack[17]=result;
    struct os_mbuf *om=ble_hs_mbuf_from_flat(last_ack,sizeof(last_ack));
    if(om) ble_gatts_notify_custom(conn,tx_handle,om);
    return 0;
}
static const struct ble_gatt_svc_def services[]={
    {.type=BLE_GATT_SVC_TYPE_PRIMARY,.uuid=&service_uuid.u,.characteristics=(struct ble_gatt_chr_def[]){
        {.uuid=&rx_uuid.u,.access_cb=access_led,.flags=BLE_GATT_CHR_F_WRITE|BLE_GATT_CHR_F_WRITE_ENC|BLE_GATT_CHR_F_WRITE_AUTHEN},
        {.uuid=&tx_uuid.u,.access_cb=access_led,.val_handle=&tx_handle,.flags=BLE_GATT_CHR_F_NOTIFY|BLE_GATT_CHR_F_READ|BLE_GATT_CHR_F_READ_ENC|BLE_GATT_CHR_F_READ_AUTHEN},
        {0}
    }},
    {0}
};
static void advertise(void) {
    struct ble_hs_adv_fields fields={0};
    fields.flags=BLE_HS_ADV_F_DISC_GEN|BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128=(ble_uuid128_t*)&service_uuid;fields.num_uuids128=1;fields.uuids128_is_complete=1;
    if(ble_gap_adv_set_fields(&fields)!=0)return;
    struct ble_hs_adv_fields scan={0};
    const char *name=ble_svc_gap_device_name();
    scan.name=(const uint8_t*)name;scan.name_len=strlen(name);scan.name_is_complete=1;
    ble_gap_adv_rsp_set_fields(&scan);
    struct ble_gap_adv_params params={0};params.conn_mode=BLE_GAP_CONN_MODE_UND;params.disc_mode=BLE_GAP_DISC_MODE_GEN;
    int rc=ble_gap_adv_start(own_type,NULL,BLE_HS_FOREVER,&params,gap,NULL);
    if(rc)ESP_LOGW(TAG,"Advertising error: %d",rc);
}
static int gap(struct ble_gap_event *event,void *arg) {
    (void)arg;
    switch(event->type) {
        case BLE_GAP_EVENT_CONNECT:
            if(event->connect.status!=0)advertise();
            break;
        case BLE_GAP_EVENT_DISCONNECT:
            esp_timer_stop(off_timer);output(false);advertise();break;
        case BLE_GAP_EVENT_ADV_COMPLETE:
            advertise();break;
        case BLE_GAP_EVENT_PASSKEY_ACTION: {
            if(event->passkey.params.action!=BLE_SM_IOACT_DISP)return BLE_HS_ENOTSUP;
            struct ble_sm_io io={0};io.action=BLE_SM_IOACT_DISP;io.passkey=esp_random()%1000000;
            /* Shown only in local serial console during pairing; never embedded in the app. */
            ESP_LOGI(TAG,"Código temporal para Android: %06" PRIu32,io.passkey);
            return ble_sm_inject_io(event->passkey.conn_handle,&io);
        }
        case BLE_GAP_EVENT_REPEAT_PAIRING:
            /* Do not silently replace an existing bond. Clear it deliberately during maintenance. */
            return BLE_GAP_REPEAT_PAIRING_IGNORE;
        default: break;
    }
    return 0;
}
static void on_sync(void) {
    if(ble_hs_util_ensure_addr(0)!=0 || ble_hs_id_infer_auto(0,&own_type)!=0)return;
    uint8_t mac[6];ble_hs_id_copy_addr(own_type,mac,NULL);
    char name[24];snprintf(name,sizeof(name),"VisioLED-%02X%02X",mac[1],mac[0]);
    ble_svc_gap_device_name_set(name);advertise();
}
static void host_task(void *arg) { (void)arg;nimble_port_run();nimble_port_freertos_deinit(); }
void app_main(void) {
    ESP_ERROR_CHECK(nvs_flash_init()); /* Never erase an existing board's storage automatically. */
    esp_timer_create_args_t timer={.callback=turn_off,.name="led-off"};
    ESP_ERROR_CHECK(esp_timer_create(&timer,&off_timer));
    if(CONFIG_PIPALAB_LED_GPIO>=0 && GPIO_IS_VALID_OUTPUT_GPIO(CONFIG_PIPALAB_LED_GPIO)) {
#if CONFIG_PIPALAB_LED_RGB
        led_strip_config_t config={.strip_gpio_num=CONFIG_PIPALAB_LED_GPIO,.max_leds=1};
        led_strip_rmt_config_t rmt={.resolution_hz=10000000,.flags.with_dma=false};
        led_ready=led_strip_new_rmt_device(&config,&rmt,&strip)==ESP_OK;
#else
        gpio_reset_pin(CONFIG_PIPALAB_LED_GPIO);
        /* Preload the inactive level before enabling output, especially for active-low LEDs. */
        esp_err_t level_err=gpio_set_level(CONFIG_PIPALAB_LED_GPIO,
#ifdef CONFIG_PIPALAB_LED_ACTIVE_LOW
            1
#else
            0
#endif
        );
        led_ready=level_err==ESP_OK && gpio_set_direction(CONFIG_PIPALAB_LED_GPIO,GPIO_MODE_OUTPUT)==ESP_OK;
#endif
        output(false);
    }
    ESP_LOGI(TAG,"LED %s; GPIO=%d",led_ready?"configurado":"pendiente",CONFIG_PIPALAB_LED_GPIO);
    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.sync_cb=on_sync;
    ble_hs_cfg.store_status_cb=ble_store_util_status_rr;
    ble_hs_cfg.sm_io_cap=BLE_HS_IO_DISPLAY_ONLY;
    ble_hs_cfg.sm_bonding=1;ble_hs_cfg.sm_mitm=1;ble_hs_cfg.sm_sc=1;
    ble_hs_cfg.sm_our_key_dist=BLE_SM_PAIR_KEY_DIST_ENC|BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist=BLE_SM_PAIR_KEY_DIST_ENC|BLE_SM_PAIR_KEY_DIST_ID;
    ble_svc_gap_init();ble_svc_gatt_init();
    assert(ble_gatts_count_cfg(services)==0);assert(ble_gatts_add_svcs(services)==0);
    ble_store_config_init();nimble_port_freertos_init(host_task);
}
